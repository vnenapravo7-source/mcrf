package org.mcrf.client

/** Closed prompt-bridge vocabulary. Never forward arbitrary page errors to native IPC. */
internal object CarrierCaptchaAutoPage {
    private const val PREFIX="MCRF_AUTO_CAPTCHA|"
    fun result(message:String):String? {
        if(!message.startsWith(PREFIX))return null
        val payload=message.removePrefix(PREFIX)
        if(payload.startsWith("ok|"))return payload.removePrefix("ok|").takeIf {
            it.length in 1..16384&&it.none {c->c.isISOControl()||c=='|'}&&!it.startsWith("error:",true)
        }
        return when(payload) {
            "slider"->"error:slider_detected"
            "rejected"->"error:auto_rejected"
            else->null
        }
    }
    const val SCRIPT="""(function(){
if(!window.__mcrfAutoHook){window.__mcrfAutoHook=true;
function report(d){var r=d&&d.response;if(r&&typeof r.success_token==='string')window.prompt('MCRF_AUTO_CAPTCHA|ok|'+r.success_token,'');
else if(r&&r.show_captcha_type==='slider')window.prompt('MCRF_AUTO_CAPTCHA|slider','');
else if(d&&d.error||r&&['BOT','ERROR_LIMIT','ERROR'].indexOf(r.status)>=0)window.prompt('MCRF_AUTO_CAPTCHA|rejected','');}
var f=window.fetch;window.fetch=function(){var u=arguments[0],s=typeof u==='string'?u:(u&&u.url)||'';return f.apply(this,arguments).then(function(r){if(s.indexOf('captchaNotRobot.check')>=0)r.clone().json().then(report).catch(function(){});return r;});};
var o=XMLHttpRequest.prototype.open,t=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){this.__mcrfAutoUrl=String(u);return o.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(){if((this.__mcrfAutoUrl||'').indexOf('captchaNotRobot.check')>=0)this.addEventListener('load',function(){try{report(JSON.parse(this.responseText));}catch(e){}});return t.apply(this,arguments);};}
if(document.querySelector('[class*="SliderCaptcha"],[class*="Kaleidoscope"],[class*="SwipeButton"]')){window.prompt('MCRF_AUTO_CAPTCHA|slider','');return;}
if(!window.__mcrfAutoClicked){var e=document.querySelector('label[for="not-robot-captcha-checkbox"],label.vkc__Checkbox-module__Checkbox')||document.getElementById('not-robot-captcha-checkbox');
if(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);if(r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'){window.__mcrfAutoClicked=true;e.click();}}}
})();"""
}
