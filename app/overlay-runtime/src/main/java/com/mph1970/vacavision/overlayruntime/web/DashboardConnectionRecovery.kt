package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

/** Observe HA's actual connection promise, not just delivery of an access token. */
internal object DashboardConnectionRecovery {
    fun script(generation: Int, presentation: Int) = """
        (function(){
          var promise=window.hassConnection;
          if(!promise || typeof promise.then!=='function')return;
          if(window.__vacaCheckedConnection===promise && window.__vacaCheckedPresentation===$presentation)return;
          window.__vacaCheckedConnection=promise;
          window.__vacaCheckedPresentation=$presentation;
          promise.then(function(result){
            if(result && result.conn && result.conn.connected)
              VacaDashboardConnection.connectionResult($generation,true);
          },function(error){
            // HA's ERR_CANNOT_CONNECT is 1. Authentication errors retain HA's
            // sign-in handling; arbitrary frontend exceptions must not reload.
            if(error===1)VacaDashboardConnection.connectionResult($generation,false);
          });
        })();
    """.trimIndent()
}
