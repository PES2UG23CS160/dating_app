let csrf: string | null = null;
export async function api(path:string,method='GET',body?:unknown):Promise<any>{
 if(method!=='GET'&&!csrf){const r=await fetch('/api/csrf',{credentials:'same-origin'});if(!r.ok)throw new Error('Could not establish a secure session. Refresh and try again.');csrf=(await r.json()).token;}
 const multipart=body instanceof FormData;
 const res=await fetch('/api'+path,{method,credentials:'same-origin',headers:{...(!multipart?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{'X-XSRF-TOKEN':csrf!}:{})},body:body===undefined?undefined:multipart?body:JSON.stringify(body)});
 if(!res.ok){const data=await res.json().catch(()=>({}));if(res.status===401){csrf=null;window.dispatchEvent(new Event('unfold-expired'));}throw new Error(data.message||({401:'Please sign in again.',403:'This action is not permitted. Refresh the page if your session has changed.',429:'Please slow down and try again shortly.',410:'This connection has ended.'} as Record<number,string>)[res.status]||'Unable to complete that request. Please try again.');}
 if(path==='/auth/logout')csrf=null;
 return res.json();
}
export function subscribeLive(callback:()=>void){let stopped=false,socket:WebSocket|undefined,timer:number|undefined;const open=()=>{if(stopped)return;socket=new WebSocket(`${location.protocol==='https:'?'wss':'ws'}://${location.host}/ws`);socket.onmessage=()=>callback();socket.onopen=()=>callback();socket.onclose=()=>{if(!stopped)timer=window.setTimeout(open,4000);};};open();return()=>{stopped=true;clearTimeout(timer);socket?.close();};}
