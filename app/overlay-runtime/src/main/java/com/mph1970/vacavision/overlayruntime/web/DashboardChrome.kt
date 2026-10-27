package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

/** Shared kiosk styling for real overlays and live layout previews. */
object DashboardChrome {
    fun apply(webView: android.webkit.WebView) {
        val js = """
            (function(){
              try {
                // One-time stylesheet with aggressive selectors
                var id = 'va-kiosk-style';
                var s = document.getElementById(id);
                if (!s) {
                  s = document.createElement('style');
                  s.id = id;
                  s.textContent = `
                    :root { --app-drawer-width: 0px !important; }
                    ha-sidebar, app-drawer, ha-drawer, ha-sidebar * { display: none !important; width: 0 !important; }
                    app-header, app-toolbar, ha-tabs, .header, .toolbar { display: none !important; height: 0 !important; }
                    #view, hui-view, hui-panel-view, hui-panel-lovelace { padding-top: 0 !important; }
                    body { margin-left: 0 !important; }
                    ha-app-layout app-header, ha-app-layout app-toolbar { display: none !important; }
                    ha-app-layout ha-sidebar { display: none !important; width: 0 !important; }
                    mwc-drawer, paper-drawer-panel { display: none !important; }
                    .main-title, ha-menu-button { display: none !important; }
                  `;
                  document.head.appendChild(s);
                }
                document.documentElement.style.setProperty('--app-drawer-width','0px','important');
                const elementsToHide = [
                  'ha-sidebar', 'app-drawer', 'ha-drawer', 'app-header', 'app-toolbar', 
                  'ha-tabs', 'mwc-drawer', 'paper-drawer-panel', 'ha-menu-button'
                ];
                elementsToHide.forEach(function(selector) {
                  try {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(function(el) {
                      el.style.display = 'none';
                      el.style.width = '0';
                      el.style.height = '0';
                      el.style.visibility = 'hidden';
                    });
                  } catch(e) {}
                });
                if (!window.__vaKioskObs) {
                  window.__vaKioskObs = new MutationObserver(function(){
                    try {
                      document.documentElement.style.setProperty('--app-drawer-width','0px','important');
                      elementsToHide.forEach(function(selector) {
                        try {
                          const elements = document.querySelectorAll(selector);
                          elements.forEach(function(el) {
                            el.style.display = 'none';
                            el.style.width = '0';
                            el.style.height = '0';
                            el.style.visibility = 'hidden';
                          });
                        } catch(e) {}
                      });
                    } catch(e){}
                  });
                  window.__vaKioskObs.observe(document.documentElement, {childList:true, subtree:true});
                }
              } catch(e){}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

}
