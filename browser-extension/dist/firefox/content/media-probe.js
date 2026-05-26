"use strict";(()=>{(()=>{let w=2e3,S=null,P=0,m="",R=!1,F=!1,K=null,V=[],T=!0,I=location.href,b="",x=!1,h="",_=null,B=null,y=!0,C=0,Y=!0,W=!1;function Z(){B=null,_=null,C=0,Y=!0,W=!1}let q=null,G="",N=new Set,E="tempo_dismissed_youtube_channels",ae=24*60*60*1e3,j=1e4,se=[" - YouTube Music"," - YouTube"," | YouTube Music"," - Spotify"," | Spotify"," \u2014 Spotify"," - Apple Music"," | Apple Music"," - SoundCloud"," | SoundCloud"," - Deezer"," | Deezer"," | Free Listening"," | Listen online"," | Bandcamp"," - Bandcamp"];function le(e){if(e!==I&&(I=e,b="",x=e.includes("youtube.com")||e.includes("youtu.be"),y=!0),b)return b;try{let t=new URL(e);b=t.origin+t.pathname}catch{b=""}return b}function s(e){return String(e||"").replace(/\s+/g," ").trim()}let ee=new WeakSet,ce=["play","playing","pause","ended","seeked","ratechange","volumechange","loadedmetadata","durationchange","emptied"];function ue(){V=Array.from(document.querySelectorAll("audio, video"));for(let e of V)if(!ee.has(e)){ee.add(e);for(let t of ce)e.addEventListener(t,()=>$(),{passive:!0})}T=!1}let de=new MutationObserver(e=>{for(let t of e){for(let n of t.addedNodes)if(n instanceof HTMLMediaElement||n instanceof HTMLElement&&n.querySelector?.("audio, video")){T=!0,$();return}for(let n of t.removedNodes)if(n instanceof HTMLMediaElement||n instanceof HTMLElement&&n.querySelector?.("audio, video")){T=!0;return}}});function fe(){let e=document.body||document.documentElement;e&&de.observe(e,{childList:!0,subtree:!0})}async function M(e){try{return await chrome.runtime.sendMessage(e)}catch(t){let n=t?.message||String(t);return(n.includes("Extension context invalidated")||n.includes("Could not establish connection"))&&X(),null}}async function me(){try{let t=(await chrome.storage.local.get(E))[E];if(!t)return;let n=Date.now(),r={};for(let[o,i]of Object.entries(t))typeof i=="number"&&n-i<ae&&(N.add(o),r[o]=i);Object.keys(r).length!==Object.keys(t).length&&await chrome.storage.local.set({[E]:r})}catch{}}async function pe(e){N.add(e);try{let n=(await chrome.storage.local.get(E))[E]??{};n[e]=Date.now(),await chrome.storage.local.set({[E]:n})}catch{}}function L(){q?.remove(),q=null,G=""}function ge(e,t){let n=s(e);if(!n)return;let r=n.toLowerCase();if(N.has(r)||q&&G===r)return;L(),G=r;let o=document.createElement("div");o.style.cssText=["position:fixed","right:18px","bottom:18px","z-index:2147483647","font-family:Inter,Roboto,Arial,sans-serif"].join(";");let i=o.attachShadow({mode:"closed"}),l=t?`<div class="tempo-title">${te(t)}</div>`:"";i.innerHTML=`
      <style>
        .tempo-card {
          width: min(320px, calc(100vw - 36px));
          color: #f8fafc;
          background: rgba(17, 24, 39, 0.96);
          border: 1px solid rgba(255, 255, 255, 0.14);
          border-radius: 12px;
          box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35);
          padding: 14px;
          backdrop-filter: blur(12px);
        }
        .tempo-kicker {
          color: #a78bfa;
          font-size: 11px;
          font-weight: 700;
          letter-spacing: 0.04em;
          text-transform: uppercase;
          margin-bottom: 5px;
        }
        .tempo-copy {
          font-size: 13px;
          line-height: 1.35;
          margin-bottom: 4px;
        }
        .tempo-channel {
          font-weight: 700;
        }
        .tempo-title {
          color: #cbd5e1;
          font-size: 12px;
          line-height: 1.35;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          margin-bottom: 12px;
        }
        .tempo-actions {
          display: flex;
          justify-content: flex-end;
          gap: 8px;
          margin-top: 12px;
        }
        .tempo-actions-secondary {
          margin-right: auto;
        }
        button {
          border: 0;
          border-radius: 8px;
          cursor: pointer;
          font-size: 12px;
          font-weight: 700;
          padding: 8px 10px;
        }
        .tempo-dismiss {
          color: #cbd5e1;
          background: rgba(255, 255, 255, 0.08);
        }
        .tempo-block {
          color: #fecaca;
          background: rgba(248, 113, 113, 0.14);
        }
        .tempo-allow {
          color: white;
          background: linear-gradient(135deg, #7c5cff, #651dad);
        }
      </style>
      <div class="tempo-card" role="dialog" aria-live="polite">
        <div class="tempo-kicker">Tempo YouTube tracking</div>
        <div class="tempo-copy">Track music from <span class="tempo-channel">${te(n)}</span>?</div>
        ${l}
        <div class="tempo-actions">
          <button class="tempo-block tempo-actions-secondary" type="button">Never allow</button>
          <button class="tempo-dismiss" type="button">Not now</button>
          <button class="tempo-allow" type="button">Allow channel</button>
        </div>
      </div>
    `,i.querySelector(".tempo-dismiss")?.addEventListener("click",()=>{pe(r),L()}),i.querySelector(".tempo-block")?.addEventListener("click",async()=>{await M({type:"BLOCK_YOUTUBE_CHANNEL",channel:n}),N.add(r),L()}),i.querySelector(".tempo-allow")?.addEventListener("click",async()=>{await M({type:"ADD_YOUTUBE_CHANNEL",channel:n}),L(),m="",setTimeout(U,100)}),document.documentElement.appendChild(o),q=o}function te(e){let t=document.createElement("div");return t.textContent=e,t.innerHTML}function ye(e){if(e?.reason==="youtube_channel_not_allowed"&&e.channel){ge(String(e.channel),String(e.title??""));return}(e?.tracked||e?.reason!=="youtube_channel_not_allowed")&&L()}function X(){R||(R=!0,console.log("[Tempo] Extension context invalidated, attempting reconnect..."),J(),setTimeout(()=>{try{chrome.runtime?.id?(console.log("[Tempo] Reconnected successfully"),R=!1,z()):setTimeout(()=>{R=!1,X()},3e4)}catch{setTimeout(()=>{R=!1,X()},3e4)}},5e3))}async function be(){try{let e=await M({type:"GET_POLLING_INTERVAL"});if(e&&typeof e.pollingIntervalSeconds=="number"){let t=Math.max(1,e.pollingIntervalSeconds)*1e3;t!==w&&(w=t,D())}}catch{}}function he(e,t){if(t!=="local")return;let r=e.settings?.newValue?.pollingIntervalSeconds;if(typeof r!="number")return;let o=Math.max(1,r)*1e3;o!==w&&(w=o,D())}function ve(e){return e!==I&&(I=e,b="",x=e.includes("youtube.com")||e.includes("youtu.be"),y=!0),x}function we(e){let t=e;for(let n of se)if(t.endsWith(n)){t=t.substring(0,t.length-n.length);break}return s(t)}function Te(){if(!y&&h)return h;let e=document.querySelector(".subtitle .yt-formatted-string");if(e?.textContent?.trim())return h=s(e.textContent),y=!1,h;let t=document.querySelector("#owner #channel-name a, ytd-video-owner-renderer #channel-name a, .ytd-channel-name a");if(t?.textContent?.trim())return h=s(t.textContent),y=!1,h;let n=document.querySelector('span[itemprop="author"] link[itemprop="name"]');return n?.getAttribute("content")?(h=s(n.getAttribute("content")),y=!1,h):(h="",y=!1,"")}function Me(e){try{let t=new URL(e);return t.hostname.includes("youtu.be")?t.pathname.substring(1):t.searchParams.get("v")}catch{return null}}function Se(){let e=null,t=n=>{e=n.detail};return window.addEventListener("tempo-response-yt-metadata",t,{once:!0}),window.dispatchEvent(new CustomEvent("tempo-request-yt-metadata")),window.removeEventListener("tempo-response-yt-metadata",t),e}function O(e,t=[]){if(!e||typeof e!="object")return t;if(e.carouselLockupRenderer)t.push(e.carouselLockupRenderer);else if(Array.isArray(e))for(let n of e)O(n,t);else for(let n of Object.keys(e))n==="streamingData"||n==="playerAds"||n==="attestation"||O(e[n],t);return t}function H(e,t=[]){if(!e||typeof e!="object")return t;if(e.metadataRowRenderer)t.push(e.metadataRowRenderer);else if(Array.isArray(e))for(let n of e)H(n,t);else for(let n of Object.keys(e))n==="streamingData"||n==="playerAds"||n==="attestation"||H(e[n],t);return t}function A(e){if(!e)return"";if(typeof e=="string")return e;if(typeof e=="object"){if(e.simpleText)return e.simpleText;if(Array.isArray(e.runs))return e.runs.map(t=>t.text||"").join("")}return""}function ne(e){let t=e.infoRows;if(!t||!Array.isArray(t))return null;let n,r,o,i;for(let l of t){let c=l.infoRowRenderer;if(!c)continue;let a=A(c.title).trim().toLowerCase(),u=A(c.defaultMetadata||c.expandedMetadata).trim();!a||!u||(a.includes("song")||a.includes("track")?n=s(u):a.includes("artist")||a.includes("singer")||a.includes("performed by")?r=s(u):a.includes("album")?o=s(u):(a.includes("label")||a.includes("record label")||a.includes("licensed to"))&&(i=s(u)))}return n||r||o||i?{title:n,artist:r,album:o,label:i}:null}function xe(e){let t,n,r,o;for(let i of e){let l=A(i.title).trim().toLowerCase(),c="";Array.isArray(i.contents)?c=i.contents.map(a=>A(a)).join(", ").trim():c=A(i.contents).trim(),!(!l||!c)&&(l.includes("song")||l.includes("track")?t||(t=s(c)):l.includes("artist")||l.includes("singer")||l.includes("performed by")?n||(n=s(c)):l.includes("album")?r||(r=s(c)):(l.includes("label")||l.includes("record label")||l.includes("licensed to"))&&(o||(o=s(c))))}return t||n||r||o?{title:t,artist:n,album:r,label:o}:null}function De(){return((document.querySelector("#description-inline-expander")||document.querySelector("#description")||document.querySelector("ytd-text-inline-expander")||document.querySelector(".ytd-video-secondary-info-renderer"))?.textContent||"").trim()}function Ee(e){if(!e)return null;let t=e.split(`
`),n,r,o,i=/^\s*(song\s*name|song|track|title|music\s*name|music)\s*[\-–—:|~]\s*(.+)$/i,l=/^\s*(singer|singers|artist|artists|performed\s+by|vocals|vocals\s+by|music\s+by|composed\s+by|written\s+by|created\s+by|sung\s+by|vocalist)\s*[\-–—:|~]\s*(.+)$/i,c=/^\s*(album|mixtape|ep|lp|single)\s*[\-–—:|~]\s*(.+)$/i,a=/^\s*(label|record\s*label|distributed\s*by|released\s*by|under)\s*[\-–—:|~]\s*(.+)$/i,u;for(let p of t){let f=p.trim();if(f&&!f.match(/^(https?:|www\.|click\s+to|instagram|facebook|twitter|youtube|subscribe|follow)/i)&&!f.match(/^(director|dop|editor|choreographer|production|bts|casting|art\s+director|light|camera|executive|artwork|hair|makeup|stylist)/i)){if(!n){let d=f.match(i);if(d){n=s(d[2]);continue}}if(!r){let d=f.match(l);if(d){let g=s(d[2]);g=g.replace(/\s+(?:and|&|x)\s+/gi,", "),r=g;continue}}if(!o){let d=f.match(c);if(d){o=s(d[2]);continue}}if(!u){let d=f.match(a);d&&(u=s(d[2]))}}}return n||r||o||u?{title:n,artist:r,album:o,label:u}:null}function ie(){let e=document.querySelectorAll("ytd-metadata-row-renderer");if(!e.length)return null;let t,n,r,o;for(let i of Array.from(e)){let l=i.querySelector("#label")||i.querySelector(".label"),c=i.querySelector("#content")||i.querySelector(".content");if(!l||!c)continue;let a=(l.textContent||"").trim().toLowerCase(),u=(c.textContent||"").trim();u&&(a.includes("song")||a.includes("track")?t=s(u):a.includes("artist")||a.includes("singer")?n=s(u):a.includes("album")?r=s(u):(a.includes("label")||a.includes("record label")||a.includes("licensed to"))&&(o=s(u)))}return t||n||r||o?{title:t,artist:n,album:r,label:o}:null}function Le(){let e=navigator.mediaSession?.metadata,t=s(e?.title),n=s(e?.artist),r=s(e?.album);if(!n){let g=document.querySelector('meta[name="music:musician"],meta[property="music:musician"],meta[name="og:audio:artist"],meta[property="og:audio:artist"]');g?.content&&(n=s(g.content))}T&&ue();let o=V,i=o.find(g=>!g.paused&&!g.ended)??o.find(g=>(g.currentTime||0)>0||Number.isFinite(g.duration))??null,c=navigator.mediaSession?.playbackState==="playing"||(i?!i.paused&&!i.ended:!1),a=i?.duration??NaN,u=i?.currentTime??NaN,p=i?.volume??-1,f=i?.muted??!1,d=i?.playbackRate??1;return t||(t=we(document.title)),!n&&ve(location.href)&&(n=Te()),!t&&!c?null:{title:t,artist:n,album:r,isPlaying:c,duration:a,position:u,volume:p,isMuted:f,playbackRate:d}}function ke(e){if(!e)return"";let t=Number.isFinite(e.position)?Math.round(e.position):-1;return`${e.title}|${e.artist}|${e.album}|${e.isPlaying?1:0}|${t}`}function U(){K=null;let e=Le();if(!e){m!==""&&(M({type:"MEDIA_STOPPED"}),m=""),L(),F=!1,D();return}if(F=e.isPlaying,D(),x&&Y&&C<5){let n=Me(location.href),r=Se(),o=r?.playerResponse,i=r?.initialData;if(n&&o?.videoDetails?.videoId===n){let l=o.videoDetails.shortDescription||"",c=Ee(l),a=null,u=O(o);for(let p of u){let f=ne(p);if(f&&f.title){a=f;break}}if(!a&&i){let p=O(i);for(let f of p){let d=ne(f);if(d&&d.title){a=d;break}}}if(!a){let p=H(o).concat(i?H(i):[]);a=xe(p)}a||(a=ie()),B=c||null,_=a||null,Y=!1,!a&&!W&&(W=!0,setTimeout(()=>{let p=ie();p&&(_=p,m="")},2e3))}else C++,C>=5&&(Y=!1)}let t=ke(e);t!==m&&(m=t,M({type:"MEDIA_STATE_UPDATE",data:{url:le(location.href),title:e.title,artist:e.artist,album:e.album,duration:e.duration,position:e.position,isPlaying:e.isPlaying,volume:e.volume,isMuted:e.isMuted,playbackRate:e.playbackRate,timestamp:Date.now(),ytDescriptionMetadata:B||void 0,ytMusicTagMetadata:_||void 0}}).then(ye))}function oe(){return document.hidden?Math.max(w*5,j):F?w:Math.max(w*5,j)}function D(){!S||oe()===P||(J(),z(!1))}function $(e=100){K||(K=setTimeout(U,e))}function z(e=!0){S||(P=oe(),S=setInterval(U,P),e&&U())}function J(){S&&(clearInterval(S),S=null,P=0)}me().finally(z),be(),fe(),chrome.storage.onChanged.addListener(he),document.addEventListener("visibilitychange",()=>{document.hidden?D():(y=!0,T=!0,m="",J(),z())}),window.addEventListener("beforeunload",()=>{m!==""&&(M({type:"MEDIA_STOPPED"}),m="")}),window.addEventListener("pagehide",()=>{m!==""&&(M({type:"MEDIA_STOPPED"}),m="")});let v=location.href,k=null,re=document.querySelector("title");re&&new MutationObserver(()=>{k||(k=setTimeout(()=>{k=null,location.href!==v&&(v=location.href,b="",x=v.includes("youtube.com")||v.includes("youtu.be"),y=!0,T=!0,Z(),m="",$(300))},500))}).observe(re,{childList:!0});let Re=history.pushState,Ae=history.replaceState;function Q(){k||(k=setTimeout(()=>{k=null,location.href!==v&&(v=location.href,b="",x=v.includes("youtube.com")||v.includes("youtu.be"),y=!0,T=!0,Z(),m="",$(300))},500))}history.pushState=function(...e){Re.apply(this,e),Q()},history.replaceState=function(...e){Ae.apply(this,e),Q()},window.addEventListener("popstate",Q)})();})();
