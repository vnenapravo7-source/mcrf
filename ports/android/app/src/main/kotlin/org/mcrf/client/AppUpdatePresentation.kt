package org.mcrf.client

object AppUpdatePresentation {
    private val version=Regex("^(?:android-)?v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    private fun parsed(value:String)=value.takeIf{it.length<=128}?.let{version.matchEntire(it)}
    fun newer(remote:String,installed:String):Boolean {
        val a=parsed(remote)?:return false;val b=parsed(installed)?:return false
        for(i in 1..3){val c=a.groupValues[i].toBigInteger().compareTo(b.groupValues[i].toBigInteger());if(c!=0)return c>0}
        val x=a.groupValues[4];val y=b.groupValues[4]
        if(x==y)return false
        if(x.isEmpty()||y.isEmpty())return x.isEmpty()
        val left=x.split('.');val right=y.split('.')
        for(i in 0 until minOf(left.size,right.size)) {
            val p=left[i];val q=right[i];if(p==q)continue
            val pn=p.all(Char::isDigit)&&p.isNotEmpty();val qn=q.all(Char::isDigit)&&q.isNotEmpty()
            return if(pn&&qn)p.toBigInteger()>q.toBigInteger() else if(pn!=qn)!pn else p>q
        }
        return left.size>right.size
    }
    fun compact(value:String):String=parsed(value)?.let{"v${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}"}?:"—"
    /** Different prereleases must not both appear as “v0.1.0” in an update confirmation. */
    fun buildLabel(value:String):String=parsed(value)?.let {
        "v${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}"+
            it.groupValues[4].takeIf(String::isNotEmpty)?.let {suffix->"-$suffix"}.orEmpty()
    }?:"—"
    fun summary(notes:String):String {
        val lines=notes.lineSequence().map{it.trim()}.filter{it.isNotBlank()&&!it.startsWith('#')&&!it.startsWith("```")}
            .take(3).map{it.removePrefix("- ").take(110)}.toList().joinToString("\n")
        return if(lines.isBlank())"Исправления и улучшения приложения."else lines.take(280)
    }
}
