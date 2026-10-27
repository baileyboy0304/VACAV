package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

/** Silent camera video discovery, with a narrowly scoped Ozolio embed adapter. */
internal object NativeCameraScript {
    val probe = """
        (function(){
          if(!window.__vacaNativeCamera){
            let sequence=0;
            const failed=new WeakSet();
            const state={candidate:null,claimed:null};
            const embeds=new Map(),embedOrigin='https://relay.ozolio.com';
            function command(c,op,block=false){
              // An iframe can remain connected to an old Document after its browsing context is gone.
              try{if(c && c.remote && c.e.isConnected && c.e.contentWindow)c.e.contentWindow.postMessage(
                {type:'vaca-ozolio',id:c.remote.id,token:c.token,op,block},embedOrigin);}catch(error){}
            }
            window.addEventListener('message',function(event){
              const m=event.data;
              if(event.origin!==embedOrigin || !m || m.type!=='vaca-ozolio-result')return;
              const record=embeds.get(m.id);
              if(!record || !record.e.isConnected || event.source!==record.e.contentWindow)return;
              record.payload=m.payload;record.received=Date.now();
            });
            state.suspend=function(id){
              if(state.claimed && state.claimed.id===id){
                state.claimed.e.__vacaNativeReady=false;state.claimed.e.__vacaNativePending=true;
              }
            };
            function restore(c){
              if(!c)return;
              c.e.__vacaNativePending=false;c.e.__vacaNativeReady=false;
              if(c.remote){command(c,'restore');return;}
              try {
                if(c.hls){c.hls.attachMedia(c.e);c.hls.startLoad();}
                else {c.e.src=c.original;c.e.load();}
                c.e.play().catch(()=>{});
              }catch(error){}
            }
            state.finish=function(id,ready,block=true){
              const c=state.claimed;if(!c || c.id!==id)return;
              if(ready){
                c.e.__vacaNativeReady=true;c.e.__vacaNativePending=false;
                // Let the camera page cancel its initial-playback timeout.
                if(!c.remote)c.e.dispatchEvent(new c.win.Event('playing'));
              }else {
                if(block)failed.add(c.e);
                if(c.remote){command(c,'restore',block);c.e.__vacaNativeReady=false;c.e.__vacaNativePending=false;}
                else restore(c);
                state.claimed=null;
              }
            };
            state.claim=function(id){
              const c=state.candidate;if(!c || c.id!==id || !c.e.isConnected)return false;
              if(c.remote && c.remote.payload.claimed!==true){command(c,'claim');return false;}
              if(state.claimed && state.claimed!==c)restore(state.claimed);
              state.claimed=c;c.e.__vacaNativePending=true;c.e.__vacaNativeReady=false;
              if(c.remote)return true;
              try {
                c.e.pause();
                if(c.hls){c.hls.stopLoad();c.hls.detachMedia();}
                else {c.e.removeAttribute('src');c.e.load();}
                return true;
              }catch(error){state.finish(id,false);return false;}
            };
            state.scan=function(){
              const found=[];
              const seen=new Set();
              function visit(root,ox,oy,sx,sy,depth){
                if(depth>8)return;
                for(const e of root.querySelectorAll('*')){
                  if(e.shadowRoot)visit(e.shadowRoot,ox,oy,sx,sy,depth+1);
                  if(e.tagName==='IFRAME'){
                    let u;try{u=new URL(e.src);}catch(error){}
                    if(u && e.contentWindow && u.origin===embedOrigin && !u.username && !u.password && u.pathname==='/pub.api' && u.searchParams.get('cmd')==='iframe' && !failed.has(e)){
                      const rid=e.__vacaEmbedId||(e.__vacaEmbedId=++sequence);
                      seen.add(rid);
                      let record=embeds.get(rid);
                      if(!record){record={e,id:rid,payload:null,received:0,candidate:null};embeds.set(rid,record);}
                      e.contentWindow.postMessage({type:'vaca-ozolio',id:rid,op:'probe'},embedOrigin);
                      const p=record.payload,r=e.getBoundingClientRect();
                      let media;try{media=p && new URL(p.source);}catch(error){}
                      if(media && media.protocol==='https:' && !media.username && !media.password && media.hostname.endsWith('.ozolio.com') && /\.m3u8$/i.test(media.pathname) &&
                        Date.now()-record.received<2500 && p.rect && p.width>0 && p.height>0 && r.width>0 && r.height>0){
                        let c=record.candidate;
                        if(!c || c.token!==p.token || c.source!==p.source){
                          c=record.candidate={e,id:++sequence,remote:record,token:p.token,source:p.source};
                          e.__vacaNativeReady=false;e.__vacaNativePending=true;
                        }
                        const fx=r.width/p.width,fy=r.height/p.height;
                        c.rect={x:ox+(r.left+p.rect.x*fx)*sx,y:oy+(r.top+p.rect.y*fy)*sy,
                          width:p.rect.width*fx*sx,height:p.rect.height*fy*sy};c.fit=p.fit;
                        found.push(c);
                      }
                    }
                    try {
                      const d=e.contentDocument,r=e.getBoundingClientRect();
                      if(d && d.defaultView && r.width>0 && r.height>0)
                        visit(d,ox+(r.left+e.clientLeft)*sx,oy+(r.top+e.clientTop)*sy,
                          sx*r.width/(e.offsetWidth||r.width),sy*r.height/(e.offsetHeight||r.height),depth+1);
                    }catch(error){}
                  }
                  if(e.tagName!=='VIDEO' || e.controls || !e.muted || failed.has(e) || e.srcObject)continue;
                  const r=e.getBoundingClientRect(),win=e.ownerDocument.defaultView;
                  if(!win || r.width<=0 || r.height<=0 || win.getComputedStyle(e).display==='none' || win.getComputedStyle(e).visibility==='hidden')continue;
                  let c=state.claimed && state.claimed.e===e?state.claimed:null;
                  if(!c){
                    const hls=win.hls && win.hls.media===e && typeof win.hls.detachMedia==='function' && typeof win.hls.attachMedia==='function'?win.hls:null;
                    const original=e.currentSrc||e.src;
                    const source=(hls && hls.url)||original;
                    let u;try{u=new URL(source,win.location.href);}catch(error){continue;}
                    if(!/^https?:$/.test(u.protocol) || u.username || u.password || !/\.(m3u8|mp4)(?:$)/i.test(u.pathname))continue;
                    if(original && original.startsWith('blob:') && !hls)continue;
                    const id=e.__vacaNativeId||(e.__vacaNativeId=++sequence);
                    c={e,win,id,hls,original,source:u.href};
                  }
                  c.rect={x:ox+r.left*sx,y:oy+r.top*sy,width:r.width*sx,height:r.height*sy};
                  c.fit=win.getComputedStyle(e).objectFit;
                  found.push(c);
                }
              }
              visit(document,0,0,1,1,0);
              for(const [rid,record] of embeds){if(!seen.has(rid)){command(record.candidate,'restore');embeds.delete(rid);}}
              // Multiple videos/controls require browser z-ordering; do not take them over.
              if(found.length!==1){
                if(state.claimed && found.some(c=>c.e===state.claimed.e))restore(state.claimed);
                for(const c of found){if(c.remote)restore(c);}
                state.claimed=null;state.candidate=null;return null;
              }
              const c=found[0];
              if(state.claimed && state.claimed.id!==c.id)state.claimed=null;
              state.candidate=c;
              return {id:c.id,source:c.source,rect:c.rect,fit:c.fit,loop:!!c.e.loop,viewport:innerWidth};
            };
            window.__vacaNativeCamera=state;
          }
          return JSON.stringify(window.__vacaNativeCamera.scan());
        })()
    """.trimIndent()

    fun claim(id: Int) = "!!window.__vacaNativeCamera && window.__vacaNativeCamera.claim($id)===true"
    fun suspend(id: Int) = "window.__vacaNativeCamera && window.__vacaNativeCamera.suspend($id)"
    fun finish(id: Int, ready: Boolean, block: Boolean = true) = "window.__vacaNativeCamera && window.__vacaNativeCamera.finish($id,$ready,$block)"
}
