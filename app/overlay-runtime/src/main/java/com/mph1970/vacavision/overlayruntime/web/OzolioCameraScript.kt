package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

/** Runs only in the Ozolio origin, installed with WebViewCompat's origin allowlist. */
internal object OzolioCameraScript {
    const val ORIGIN = "https://relay.ozolio.com"
    val script = """
        (function(){
          if(window===top || window.__vacaOzolio)return;
          window.__vacaOzolio=true;
          let current=null,sequence=0;
          const failed=new WeakSet();
          function candidate(){
            const p=window.webcam,o=p && p.media && p.media.output;
            if(!o || !o.live || o.preroll || o.format!=='M3U8' || !o.scene || !o.scene.isConnected)return null;
            const e=o.scene,h=o.hlsjs;
            if(e.tagName!=='VIDEO' || !e.muted || e.controls || e.srcObject || failed.has(e))return null;
            let u;try{u=new URL(o.source);}catch(error){return null;}
            if(u.protocol!=='https:' || u.username || u.password || !u.hostname.endsWith('.ozolio.com') || !/\.m3u8$/i.test(u.pathname))return null;
            if(h && (typeof h.stopLoad!=='function' || typeof h.detachMedia!=='function' || typeof h.attachMedia!=='function'))return null;
            if(!current || current.e!==e || current.source!==u.href){
              restore(current,false);
              current={e,h,source:u.href,original:e.currentSrc||e.src,token:++sequence,claimed:false};
            }
            return current;
          }
          function restore(c,block){
            if(!c || !c.claimed)return;
            c.claimed=false;if(block)failed.add(c.e);
            if(!c.e.isConnected)return;
            try{
              if(c.h){c.h.attachMedia(c.e);c.h.startLoad();}
              else{c.e.src=c.original;c.e.load();}
              c.e.play().catch(()=>{});
            }catch(error){}
          }
          addEventListener('message',function(event){
            const m=event.data;
            // Only the embedding top-level page can negotiate playback. No native bridge or HA credentials.
            if(event.source!==top || !m || m.type!=='vaca-ozolio' || !Number.isInteger(m.id))return;
            let parentOrigin;try{parentOrigin=new URL(document.referrer).origin;}catch(error){return;}
            if(event.origin!==parentOrigin)return;
            let c=candidate();
            if(m.op==='claim' && c && m.token===c.token && !c.claimed){
              try{
                c.e.pause();
                if(c.h){c.h.stopLoad();c.h.detachMedia();}else{c.e.removeAttribute('src');c.e.load();}
                c.claimed=true;
              }catch(error){c.claimed=true;restore(c,true);c=null;}
            }else if(m.op==='restore' && current && m.token===current.token){
              restore(current,!!m.block);c=candidate();
            }
            let payload=null;
            if(c){
              const r=c.e.getBoundingClientRect();
              if(r.width>0 && r.height>0)payload={token:c.token,source:c.source,claimed:c.claimed,
                rect:{x:r.left,y:r.top,width:r.width,height:r.height},width:innerWidth,height:innerHeight,
                fit:getComputedStyle(c.e).objectFit};
            }
            event.source.postMessage({type:'vaca-ozolio-result',id:m.id,payload},event.origin);
          });
        })();
    """.trimIndent()
}
