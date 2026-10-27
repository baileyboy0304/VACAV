package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import org.json.JSONObject

/** Installed before HA executes. Only the controller page may register this View Assist device. */
internal object PreparedPageScript {
    fun start(path: String) = """
        (function(){
          const target=${JSONObject.quote(path)};
          let connection;
          Object.defineProperty(window,'hassConnection',{
            configurable:true,get:()=>connection,set:value=>{
              connection=Promise.resolve(value).then(result=>{
                const conn=result.conn,subscribe=conn.subscribeMessage.bind(conn);
                conn.subscribeMessage=function(callback,message,...args){
                  if(message && message.type==='view_assist/connect'){
                    let last='',registered=false;
                    function update(){
                      try {
                        const snapshot=JSON.parse(VacaPreparedPage.snapshot());
                        if(window.viewassist)window.viewassist.registered=!!snapshot?.registered;
                        if(!snapshot || !snapshot.registered || !snapshot.config)return;
                        const current=JSON.stringify(snapshot.config);
                        if(current!==last){
                          callback({event:registered?'config_update':'registered',payload:snapshot.config});
                          last=current;registered=true;
                        }
                        if(window.viewassist){
                          window.viewassist.navigation=snapshot.navigation;
                          window.viewassist.server_time_delta=snapshot.server_time_delta;
                        }
                      }catch(e){}
                    }
                    const timer=setInterval(update,1000);update();
                    return Promise.resolve(()=>clearInterval(timer));
                  }
                  return subscribe(callback,message,...args);
                };
                return result;
              });
              // HA also consumes this promise; avoid an unhandled rejection before its listener attaches.
              connection.catch(()=>{});
            }
          });
          const push=history.pushState.bind(history),replace=history.replaceState.bind(history);
          function route(value){
            if(value==null)return value;
            const u=new URL(value,location.href);
            return u.origin===location.origin && /^\/view-?assist(?:\/|$)/.test(u.pathname)?target:value;
          }
          history.pushState=(s,t,u)=>push(s,t,route(u));
          history.replaceState=(s,t,u)=>replace(s,t,route(u));
          // All preparation is silent, including cameras with an audio track.
          const play=HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play=function(){this.muted=true;return play.apply(this,arguments);};
        })();
    """.trimIndent()

    val snapshot = """
        JSON.stringify(window.viewassist?{
          config:window.viewassist.config,registered:window.viewassist.registered,
          navigation:window.viewassist.navigation,server_time_delta:window.viewassist.server_time_delta
        }:null)
    """.trimIndent()

    fun readiness(path: String) = """
        (function(){
          const ha=document.querySelector('home-assistant');
          if(location.pathname!==${JSONObject.quote(path)} || !ha?.hass?.connection?.connected)return false;
          if(!window.viewassist?.registered)return false;
          // A native handover must produce its first frame before the prepared
          // window reveals; browser readyState alone would reveal then go black.
          if(window.__vacaNativeCamera?.scan() && !window.__vacaNativeCamera.candidate.e.__vacaNativeReady)return false;
          const needsVideo=${com.mph1970.vacavision.overlayruntime.overlay.OverlayLayout.viewName(path) == "beachcam"};
          let view=false,busy=false,mediaReady=true,cards=0,videos=0;
          function visit(root){
            for(const e of root.querySelectorAll('*')){
              if(e.tagName==='HUI-VIEW')view=true;
              if(e.tagName==='HA-CARD')cards++;
              if(e.getBoundingClientRect().width>0 && e.getBoundingClientRect().height>0){
                if(e.tagName==='HA-INIT-PAGE' || e.tagName==='HA-CIRCULAR-PROGRESS')busy=true;
                if(e.tagName==='IMG' && e.currentSrc && (!e.complete || !e.naturalWidth))mediaReady=false;
                if(e.tagName==='VIDEO'){
                  videos++;
                  e.muted=true;
                  if(e.__vacaNativeReady)continue;
                  if(e.__vacaNativePending){mediaReady=false;continue;}
                  if(e.readyState<2 || !e.videoWidth)mediaReady=false;
                  if(e.requestVideoFrameCallback && !e.__vacaDecodedFrame){
                    if(!e.__vacaFramePending){
                      e.__vacaFramePending=true;
                      e.requestVideoFrameCallback(()=>{e.__vacaDecodedFrame=true;});
                    }
                    mediaReady=false;
                  }
                }
                if(e.tagName==='IFRAME'){
                  try {
                    const doc=e.contentDocument;
                    if(doc && doc.readyState==='complete')visit(doc);
                    else if(needsVideo)mediaReady=false;
                  }catch(error){if(needsVideo)mediaReady=false;}
                }
              }
              if(e.shadowRoot)visit(e.shadowRoot);
            }
          }
          visit(document);
          return view && cards>0 && !busy && mediaReady && (!needsVideo || videos>0);
        })()
    """.trimIndent()
}
