package org.mcrf.client

/** Activity-local presentation; expanding the tab never changes a connection or saved profile. */
class EdgeConnectionState {
    var collapsed=true

    enum class Action { NONE, START, STOP, SWITCH }
    companion object {
        fun indicator(whitelists:Boolean,active:Boolean,pending:Boolean,activeWhitelists:Boolean,pendingWhitelists:Boolean?):Boolean {
            if(pending&&pendingWhitelists!=null)return whitelists==pendingWhitelists
            return checked(whitelists,active,activeWhitelists)
        }
        fun checked(whitelists:Boolean,active:Boolean,activeWhitelists:Boolean)=active&&whitelists==activeWhitelists
        fun action(whitelists:Boolean,wanted:Boolean,active:Boolean,activeWhitelists:Boolean,available:Boolean):Action {
            if(!available)return Action.NONE
            if(!wanted)return if(checked(whitelists,active,activeWhitelists))Action.STOP else Action.NONE
            if(!active)return Action.START
            return if(whitelists==activeWhitelists)Action.NONE else Action.SWITCH
        }
    }
}
