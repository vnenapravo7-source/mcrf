package org.mcrf.client

/** UI-thread-only last intent while catalogue loading; no data or connection side effects. */
class DeferredNavigation {
    private var pending:String?=null
    fun capture(page:String){
        require(page.isNotBlank()&&page.length<=128&&page.none(Char::isISOControl))
        pending=page
    }
    fun take():String?=pending.also {pending=null}
}
