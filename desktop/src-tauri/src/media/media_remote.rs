//! Direct access to the macOS MediaRemote private framework.
//!
//! This replaces the `nowplaying-cli` dependency which is unreliable on recent
//! macOS versions (returns null even when data is available). The private
//! framework works because it is the same API that Control Center and the
//! Touch Bar use internally.

use core_foundation::base::{kCFAllocatorDefault, CFType, TCFType};
use core_foundation::bundle::{CFBundleGetFunctionPointerForName, CFBundleCreate};
use core_foundation::dictionary::CFDictionary;
use core_foundation::number::CFNumber;
use core_foundation::string::{CFString, CFStringRef};
use core_foundation::url::CFURL;
use log::debug;
use std::ffi::c_void;
use std::sync::Once;

/// Raw now-playing snapshot from MediaRemote.
pub struct MediaRemoteNowPlaying {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_secs: f64,
    pub elapsed_secs: f64,
    pub playback_rate: f64,
    pub bundle_id: String,
}

// C function signature:
// void MRMediaRemoteGetNowPlayingInfo(dispatch_queue_t queue, void (^handler)(CFDictionaryRef info));
type MRMediaRemoteGetNowPlayingInfoFn = unsafe extern "C" fn(
    queue: *const c_void,  // dispatch_queue_t
    handler: *const c_void, // block
);

// void MRMediaRemoteGetNowPlayingApplicationPID(dispatch_queue_t queue, void (^handler)(int pid));
type MRMediaRemoteGetNowPlayingApplicationPIDFn = unsafe extern "C" fn(
    queue: *const c_void,
    handler: *const c_void,
);

static INIT: Once = Once::new();
static mut FN_GET_INFO: Option<MRMediaRemoteGetNowPlayingInfoFn> = None;
static mut FN_GET_PID: Option<MRMediaRemoteGetNowPlayingApplicationPIDFn> = None;

/// Load the MediaRemote framework and resolve function pointers (once).
fn ensure_loaded() {
    INIT.call_once(|| {
        unsafe {
            let path = CFString::new("/System/Library/PrivateFrameworks/MediaRemote.framework");
            let url = CFURL::from_path(path.to_string(), true);
            let url_ref = match url {
                Some(u) => u,
                None => return,
            };
            let bundle = CFBundleCreate(kCFAllocatorDefault, url_ref.as_concrete_TypeRef());
            if bundle.is_null() {
                return;
            }

            let name_info = CFString::new("MRMediaRemoteGetNowPlayingInfo");
            let ptr_info = CFBundleGetFunctionPointerForName(bundle, name_info.as_concrete_TypeRef());
            if !ptr_info.is_null() {
                FN_GET_INFO = Some(std::mem::transmute::<*const c_void, MRMediaRemoteGetNowPlayingInfoFn>(ptr_info));
            }

            let name_pid = CFString::new("MRMediaRemoteGetNowPlayingApplicationPID");
            let ptr_pid = CFBundleGetFunctionPointerForName(bundle, name_pid.as_concrete_TypeRef());
            if !ptr_pid.is_null() {
                FN_GET_PID = Some(std::mem::transmute::<*const c_void, MRMediaRemoteGetNowPlayingApplicationPIDFn>(ptr_pid));
            }
        }
    });
}

/// Query the system NowPlaying center via MediaRemote.framework.
///
/// This is synchronous — it blocks on a background dispatch queue and waits
/// up to 3 seconds for the callback. Returns `None` if nothing is playing or
/// the framework cannot be loaded.
pub fn get_now_playing_info() -> Option<MediaRemoteNowPlaying> {
    ensure_loaded();

    let fn_get_info = unsafe { FN_GET_INFO }?;

    let (tx, rx) = std::sync::mpsc::channel::<Option<MediaRemoteNowPlaying>>();

    // Build an Objective-C block for the callback.
    // MRMediaRemoteGetNowPlayingInfo passes a CFDictionaryRef to the block.
    let block = block::ConcreteBlock::new(move |info_ref: *const c_void| {
        if info_ref.is_null() {
            let _ = tx.send(None);
            return;
        }

        let result = unsafe { parse_now_playing_dict(info_ref) };
        let _ = tx.send(result);
    });
    let block = block.copy();

    unsafe {
        // dispatch_get_global_queue(QOS_CLASS_DEFAULT = 0x15, 0)
        let queue = dispatch_get_global_queue(0x15, 0);
        fn_get_info(queue, &*block as *const _ as *const c_void);
    }

    match rx.recv_timeout(std::time::Duration::from_secs(3)) {
        Ok(result) => result,
        Err(_) => {
            debug!("MediaRemote: timeout waiting for NowPlayingInfo");
            None
        }
    }
}

unsafe fn parse_now_playing_dict(dict_ref: *const c_void) -> Option<MediaRemoteNowPlaying> {
    // Cast to CFDictionary — the dict_ref is a CFDictionaryRef
    let dict: CFDictionary<CFString, CFType> =
        CFDictionary::wrap_under_get_rule(dict_ref as *const _);

    let title = get_string_value(&dict, "kMRMediaRemoteNowPlayingInfoTitle")
        .unwrap_or_default();
    let artist = get_string_value(&dict, "kMRMediaRemoteNowPlayingInfoArtist")
        .unwrap_or_default();
    let album = get_string_value(&dict, "kMRMediaRemoteNowPlayingInfoAlbum")
        .unwrap_or_default();

    if title.is_empty() {
        return None;
    }

    let duration_secs = get_f64_value(&dict, "kMRMediaRemoteNowPlayingInfoDuration")
        .unwrap_or(0.0);
    let elapsed_secs = get_f64_value(&dict, "kMRMediaRemoteNowPlayingInfoElapsedTime")
        .unwrap_or(-1.0);
    let playback_rate = get_f64_value(&dict, "kMRMediaRemoteNowPlayingInfoPlaybackRate")
        .unwrap_or(-1.0);

    // Try to get the source bundle ID from the dict (some macOS versions include it)
    let bundle_id = get_string_value(&dict, "kMRMediaRemoteNowPlayingInfoClientPropertiesDeviceIdentifier")
        .or_else(|| get_string_value(&dict, "kMRMediaRemoteNowPlayingInfoClientBundleIdentifier"))
        .unwrap_or_default();

    Some(MediaRemoteNowPlaying {
        title,
        artist,
        album,
        duration_secs,
        elapsed_secs,
        playback_rate,
        bundle_id,
    })
}

unsafe fn get_string_value(dict: &CFDictionary<CFString, CFType>, key: &str) -> Option<String> {
    let cf_key = CFString::new(key);
    let value_ref = dict.find(cf_key.as_concrete_TypeRef())?;
    // The value should be a CFString
    let cf_str: CFString = CFString::wrap_under_get_rule(value_ref.as_CFTypeRef() as CFStringRef);
    let s = cf_str.to_string();
    if s.is_empty() || s == "null" || s == "(null)" {
        return None;
    }
    Some(s)
}

unsafe fn get_f64_value(dict: &CFDictionary<CFString, CFType>, key: &str) -> Option<f64> {
    let cf_key = CFString::new(key);
    let value_ref = dict.find(cf_key.as_concrete_TypeRef())?;
    let cf_num: CFNumber = CFNumber::wrap_under_get_rule(value_ref.as_CFTypeRef() as *const _);
    cf_num.to_f64()
}

extern "C" {
    fn dispatch_get_global_queue(identifier: isize, flags: usize) -> *const c_void;
}
