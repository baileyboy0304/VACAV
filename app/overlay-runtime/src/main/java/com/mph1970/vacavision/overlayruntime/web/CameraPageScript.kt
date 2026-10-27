package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

/** Keep the HA card built while its embedded camera document is unloaded. */
internal object CameraPageScript {
    val install = """
        if(window===window.top){
          let active=VacaPreparedPage.cameraActive();
          const sources=new Map();
          const descriptor=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'src');
          const attribute=Element.prototype.setAttribute;
          function source(frame,value){
            const url=String(value);
            if(!url || url==='about:blank'){sources.delete(frame);return url;}
            sources.set(frame,url);
            return active?url:'about:blank';
          }
          Object.defineProperty(HTMLIFrameElement.prototype,'src',{
            ...descriptor,set(value){descriptor.set.call(this,source(this,value));}
          });
          Element.prototype.setAttribute=function(name,value){
            if(this instanceof HTMLIFrameElement && String(name).toLowerCase()==='src'){
              value=source(this,value);
            }
            return attribute.call(this,name,value);
          };
          // HA/Lit also creates iframe markup through the HTML parser, bypassing
          // property setters. Watch each shadow root as it is attached as well.
          function inspect(frame){
            if(!(frame instanceof HTMLIFrameElement))return;
            const url=frame.getAttribute('src');
            if(url && url!=='about:blank'){
              sources.set(frame,url);
              if(!active)attribute.call(frame,'src','about:blank');
            }
          }
          const roots=new WeakSet();
          function observe(root){
            if(roots.has(root))return;roots.add(root);
            function scan(node){
              if(node instanceof HTMLIFrameElement)inspect(node);
              if(node.shadowRoot)observe(node.shadowRoot);
              for(const child of node.querySelectorAll?.('*')||[]){
                if(child instanceof HTMLIFrameElement)inspect(child);
                if(child.shadowRoot)observe(child.shadowRoot);
              }
            }
            new MutationObserver(records=>{
              for(const record of records){
                if(record.type==='attributes')inspect(record.target);
                else for(const node of record.addedNodes)scan(node);
              }
            }).observe(root,{subtree:true,childList:true,attributes:true,attributeFilter:['src']});
            scan(root);
          }
          const attach=Element.prototype.attachShadow;
          Element.prototype.attachShadow=function(options){const root=attach.call(this,options);observe(root);return root;};
          observe(document);
          window.__vacaCanParkCamera=function(){
            let inlineMedia=false;
            function visit(root){
              for(const e of root.querySelectorAll('*')){
                if((e.tagName==='VIDEO' || e.tagName==='AUDIO') &&
                  (e.__vacaNativePending || e.__vacaNativeReady || ((e.currentSrc || e.srcObject) && !e.paused && !e.ended)))inlineMedia=true;
                if(e.shadowRoot)visit(e.shadowRoot);
              }
            }
            visit(document);
            return !inlineMedia && [...sources.keys()].some(frame=>frame.isConnected);
          };
          window.__vacaSetCameraActive=function(value){
            active=!!value;
            for(const [frame,url] of sources){
              if(!frame.isConnected){sources.delete(frame);continue;}
              const desired=active?url:'about:blank';
              if(frame.getAttribute('src')!==desired)attribute.call(frame,'src',desired);
            }
            return sources.size;
          };
        }
    """.trimIndent()
}
