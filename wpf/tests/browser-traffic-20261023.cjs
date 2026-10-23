const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),http=require('http');
const root=path.resolve(process.argv[2]);fs.mkdirSync(root,{recursive:true});
const urls=process.argv.includes('--embed')?['https://www.youtube-nocookie.com/embed/GU0HEi1rHL4?autoplay=1&mute=1']:process.argv.includes('--youtube-only')?['https://www.youtube.com/watch?v=GU0HEi1rHL4']:process.argv.includes('--russia-only')?['https://www.ozon.ru/cart','https://www.avito.ru/']:process.argv.includes('--short')?['https://www.youtube.com/watch?v=GU0HEi1rHL4','https://www.twitch.tv/directory/all','https://2ip.io/','https://github.com/vnenapravo7-source?tab=repositories']:['https://www.youtube.com/watch?v=GU0HEi1rHL4','https://www.twitch.tv/directory/all','https://2ip.io/','https://www.ozon.ru/cart','https://claude.ai/new','https://login.tailscale.com/admin/machines','https://github.com/vnenapravo7-source?tab=repositories','https://www.avito.ru/'];
function safe(s){try{const u=new URL(s);return u.host+u.pathname.substring(0,110)}catch{return String(s).slice(0,100)}}
async function rejectOptionalCookies(page){for(const frame of page.frames()){for(const text of ['Отклонить все','Reject all']){const b=frame.getByText(text,{exact:true}).first();if(await b.isVisible().catch(()=>false)){await b.click({timeout:3000}).catch(()=>{});return true}}}return false}
async function videoFrame(page){for(const frame of page.frames())if(await frame.locator('video').count().catch(()=>0))return frame;return page}
(async()=>{
 let localServer=null;
 if(process.argv.includes('--embed')){localServer=http.createServer((q,r)=>{r.writeHead(200,{'Content-Type':'text/html','Referrer-Policy':'strict-origin-when-cross-origin'});r.end('<!doctype html><title>Public video transport test</title><iframe width="1360" height="820" allow="autoplay; encrypted-media" src="https://www.youtube-nocookie.com/embed/GU0HEi1rHL4?autoplay=1&mute=1"></iframe>')});await new Promise(r=>localServer.listen(0,'127.0.0.1',r));urls[0]='http://127.0.0.1:'+localServer.address().port+'/'}
 const context=await chromium.launchPersistentContext(path.join(root,'isolated-browser'),{executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,viewport:{width:1400,height:900},ignoreHTTPSErrors:false,args:['--no-proxy-server','--autoplay-policy=no-user-gesture-required','--disable-background-networking','--disable-sync','--no-first-run',...(process.argv.includes('--no-quic')?['--disable-quic']:[])]});
 const results=[];
 try{for(let i=0;i<urls.length;i++){
  const page=await context.newPage(),requests=[],failures=[],statuses={},start=Date.now();let closing=false;
  await page.bringToFront();
  const session=await context.newCDPSession(page);await session.send('Network.enable');const protocols={},media=new Map();session.on('Network.responseReceived',e=>{protocols[e.response.protocol]=(protocols[e.response.protocol]||0)+1;if(/googlevideo\.com|ttvnw\.net|twitch\.tv\/api\/channel/.test(e.response.url))media.set(e.requestId,{url:safe(e.response.url),status:e.response.status,protocol:e.response.protocol,bytes:0})});session.on('Network.dataReceived',e=>{if(media.has(e.requestId))media.get(e.requestId).bytes+=e.dataLength});
  page.on('request',r=>requests.push({url:safe(r.url()),type:r.resourceType()}));page.on('response',r=>{statuses[r.status()]=(statuses[r.status()]||0)+1});
  page.on('requestfailed',r=>{if(!closing)failures.push({url:safe(r.url()),type:r.resourceType(),error:r.failure()?.errorText})});
  let navigation='ok';try{await page.goto(urls[i],{waitUntil:'domcontentloaded',timeout:25000})}catch(e){navigation=e.message.split('\n')[0]}
  if(process.argv.includes('--media')&&urls[i].includes('youtube'))await rejectOptionalCookies(page);
  try{if(urls[i].includes('youtube'))await page.locator('.ytp-large-play-button').click({timeout:2000})}catch{}
  await page.waitForTimeout(urls[i].includes('youtube')||process.argv.includes('--embed')?20000:10000);
  if(process.argv.includes('--media')&&urls[i].includes('youtube')){await rejectOptionalCookies(page);await page.waitForTimeout(1000)}
  let playback=null;
  if(process.argv.includes('--media')&&urls[i].includes('twitch')){const href=await page.locator('a[data-a-target="preview-card-image-link"]').first().getAttribute('href',{timeout:1500}).catch(()=>null);if(href&&/^\/[A-Za-z0-9_]+$/.test(href)){await page.goto('https://www.twitch.tv'+href,{waitUntil:'domcontentloaded',timeout:25000}).catch(()=>{});await page.waitForTimeout(12000)}}
  if(process.argv.includes('--media')&&(/youtube|twitch/.test(urls[i])||process.argv.includes('--embed'))){const frame=await videoFrame(page);playback=await frame.evaluate(async()=>{const v=document.querySelector('video');if(!v)return {error:'No video element'};v.muted=true;let error=null;await Promise.race([v.play().catch(e=>error=e.message),new Promise(r=>setTimeout(r,2000))]);return {start:v.currentTime,error,visibility:document.visibilityState}});await page.waitForTimeout(10000)}
  let view;try{view=await page.evaluate(()=>({title:document.title,images:[...document.images].filter(x=>/^https?:/.test(x.currentSrc)).length,brokenImages:[...document.images].filter(x=>/^https?:/.test(x.currentSrc)&&x.complete&&x.naturalWidth===0).length,video:[...document.querySelectorAll('video')].map(v=>({time:v.currentTime,ready:v.readyState,network:v.networkState,paused:v.paused,error:v.error?.code||null})),text:document.body.innerText.slice(0,450)}))}catch(e){view={error:e.message.split('\n')[0]}}
  if(process.argv.includes('--embed'))view.video=await (await videoFrame(page)).evaluate(()=>[...document.querySelectorAll('video')].map(v=>({time:v.currentTime,ready:v.readyState,network:v.networkState,paused:v.paused,error:v.error?.code||null})));
  await page.screenshot({path:path.join(root,'page-'+i+'.png')}).catch(()=>{});
  const result={url:urls[i],elapsed:Date.now()-start,navigation,requests:requests.length,statuses,protocols,failures,playback,media:[...media.values()],view};results.push(result);fs.writeFileSync(path.join(root,'results.json'),JSON.stringify(results,null,2));console.log(JSON.stringify({url:urls[i],elapsed:result.elapsed,navigation,requests:requests.length,statuses,protocols,failures:failures.length,playback,mediaBytes:result.media.reduce((s,r)=>s+r.bytes,0),video:view?.video,title:view?.title}));
  closing=true;await page.close();
 }}finally{await context.close();if(localServer)await new Promise(r=>localServer.close(r))}
})().catch(e=>{console.error(e.message);process.exitCode=1});
