package com.mph1970.vacavision.overlayruntime.overlay

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import org.json.JSONObject

/** Prevent View Assist's registration/reauthentication clock redirect from replacing an active overlay. */
internal object ActiveDashboardNavigation {
    fun script(path: String?) = """
        (function(){
          window.__vacaActiveDashboard=${path?.let(JSONObject::quote) ?: "null"};
          if(!window.__vacaIdleHistory){
            var push=history.pushState.bind(history), replace=history.replaceState.bind(history);
            window.__vacaIdleHistory={push:push,replace:replace};
            function route(value){
              if(value==null || !window.__vacaActiveDashboard)return value;
              var u=new URL(value,location.href);
              return u.origin===location.origin && /^\/view-?assist\/clock\/?${'$'}/.test(u.pathname)
                ? window.__vacaActiveDashboard : value;
            }
            history.pushState=function(s,t,u){return push(s,t,route(u));};
            history.replaceState=function(s,t,u){return replace(s,t,route(u));};
          }
          // A fresh document or a back/forward navigation can bypass the history wrappers.
          // Restore its content without requesting another show or restarting the duration.
          if(window.__vacaActiveDashboard && /^\/view-?assist\/clock\/?${'$'}/.test(location.pathname)){
            window.__vacaIdleHistory.replace(null,'',window.__vacaActiveDashboard);
            window.dispatchEvent(new CustomEvent('location-changed'));
            window.dispatchEvent(new Event('popstate'));
          }
        })();
    """.trimIndent()
}
