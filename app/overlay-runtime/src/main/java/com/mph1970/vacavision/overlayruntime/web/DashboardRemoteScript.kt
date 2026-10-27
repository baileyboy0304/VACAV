package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import org.json.JSONObject

/** Card contract: cancel vaca-overlay-remote synchronously to claim an action. */
internal object DashboardRemoteScript {
    fun dispatch(path: String, action: String) = """
        (function(){
          if(location.pathname!==${JSONObject.quote(path)})return false;
          const action=${JSONObject.quote(action)};
          const event=new CustomEvent('vaca-overlay-remote',{
            cancelable:true,detail:{version:1,action,path:location.pathname}
          });
          window.dispatchEvent(event);
          return event.defaultPrevented;
        })()
    """.trimIndent()
}
