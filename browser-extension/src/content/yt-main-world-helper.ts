// ============================================================================
// Tempo Stats — Content Script Helper: YouTube Main World Helper
// Runs in the page's MAIN world context to access global window variables
// that content scripts in the ISOLATED world cannot read directly.
// ============================================================================

(() => {
  window.addEventListener('tempo-request-yt-metadata', () => {
    try {
      const response = {
        playerResponse: (window as any).ytInitialPlayerResponse || null,
        initialData: (window as any).ytInitialData || null,
      };
      window.dispatchEvent(new CustomEvent('tempo-response-yt-metadata', {
        detail: response
      }));
    } catch (e) {
      console.warn('[Tempo] Error dispatching YouTube main-world metadata:', e);
    }
  });
})();
