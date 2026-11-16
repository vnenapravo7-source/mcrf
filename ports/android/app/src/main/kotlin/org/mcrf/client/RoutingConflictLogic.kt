package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.Locale

/** Bounded DNF over destination domains/IPs and Android owners. Unknown conditions
 * stay unknown; they must never prove disjointness or complete shadowing. */
internal class RoutingConflictLogic(
    private val resolve:(String)->JSONObject?,private val label:(String)->String
) {
    class Limit:RuntimeException()
    private var remaining=1_000_000
    fun step(count:Int=1) {remaining-=count;if(remaining<0||Thread.currentThread().isInterrupted)throw Limit()}
    data class Pattern(val name:String,val suffix:Boolean)
    class Names(val patterns:Set<Pattern>) {
        private val exact=patterns.filterNot {it.suffix}.mapTo(hashSetOf()){it.name}
        private val suffix=patterns.filter {it.suffix}.mapTo(hashSetOf()){it.name}
        fun suffixContains(name:String):Boolean {
            var current=name
            while(true) {if(current in suffix)return true;val dot=current.indexOf('.');if(dot<0)return false;current=current.substring(dot+1)}
        }
        fun contains(name:String)=name in exact||suffixContains(name)
    }
    data class Range(val bits:Int,val lo:BigInteger,val hi:BigInteger)
    sealed class Atom {
        data class Domain(val names:Names,val inverse:Boolean=false,val origin:String="domain"):Atom()
        data class Ip(val ranges:List<Range>,val inverse:Boolean=false,val origin:String="IP"):Atom()
        data class App(val packages:Set<String>,val inverse:Boolean=false,val origin:String="app"):Atom()
        data class Unknown(val reason:String):Atom()
    }
    data class Expr(val clauses:List<List<Atom>>)
    enum class Kind { EMPTY, EXACT, POSSIBLE }
    data class Match(val kind:Kind,val explanation:String="")
    private val yes=Expr(listOf(emptyList()))
    private val no=Expr(emptyList())
    private fun uncertain(reason:String)=Expr(listOf(listOf(Atom.Unknown(reason))))
    fun either(parts:List<Expr>):Expr {
        if(parts.sumOf {it.clauses.size}>64)return uncertain("Сложное объединение: требуется ручная проверка")
        return Expr(parts.flatMap {it.clauses})
    }
    fun both(a:Expr,b:Expr):Expr {
        if(a.clauses.isEmpty()||b.clauses.isEmpty())return no
        if(a.clauses.size*b.clauses.size>64)return uncertain("Сложное пересечение: требуется ручная проверка")
        val clauses=a.clauses.flatMap {left->b.clauses.map {right->left+right}}
        return if(clauses.any {it.size>128})uncertain("Слишком много совместных условий")else Expr(clauses)
    }
    fun not(value:Expr):Expr {
        fun invert(atom:Atom):Atom=when(atom) {
            is Atom.Domain->atom.copy(inverse=!atom.inverse)
            is Atom.Ip->atom.copy(inverse=!atom.inverse)
            is Atom.App->atom.copy(inverse=!atom.inverse)
            is Atom.Unknown->atom
        }
        return value.clauses.fold(yes){result,clause->both(result,Expr(clause.map {listOf(invert(it))}))}
    }
    fun parse(node:JSONObject,origin:String="",depth:Int=0,stack:Set<String> = emptySet()):Expr {
        step();if(depth>16)return uncertain("Слишком глубокое условие")
        val result=if(node.optString("type")=="logical") {
            val children=node.optJSONArray("rules")?:return uncertain("Неизвестное логическое условие")
            if(children.length()>256)return uncertain("Слишком много вложенных условий")
            val parsed=(0 until children.length()).map {parse(children.getJSONObject(it),origin,depth+1,stack)}
            when(node.optString("mode")){"or"->either(parsed);"and"->parsed.fold(yes,::both);else->uncertain("Неизвестный логический оператор")}
        } else basic(node,origin,depth,stack)
        return if(node.optBoolean("invert"))not(result)else result
    }
    private fun strings(node:JSONObject,key:String):List<String> {
        val raw=node.opt(key)?:return emptyList()
        val values=if(raw is JSONArray)(0 until raw.length()).map {raw.getString(it)}else listOf(raw.toString())
        step(values.size);return values
    }
    private fun basic(node:JSONObject,origin:String,depth:Int,stack:Set<String>):Expr {
        if(node.keys().asSequence().any {it in setOf("geoip","geosite","ip_is_private","ip_version","process_name","process_path","process_path_regex","user","user_id","rule_set_ip_cidr_match_source","rule_set_ip_cidr_accept_empty")})return uncertain("Дополнительное IP/owner-условие требует ручной проверки")
        // A single default rule-set may merge fields into its outer rule. Do not
        // assume AND in this case: https://sing-box.sagernet.org/configuration/route/rule/
        if(node.has("rule_set")&&node.keys().asSequence().any {it !in setOf("type","invert","rule_set")})return uncertain("Набор с внешними условиями: требуется ручная проверка объединения полей")
        val parts=mutableListOf<Expr>()
        val domain=linkedSetOf<Pattern>()
        for(key in listOf("domain","domain_suffix"))for(value in strings(node,key))domain.add(Pattern(value.trimStart('.').lowercase(Locale.ROOT),key=="domain_suffix"))
        val domainParts=mutableListOf<Expr>()
        if(domain.isNotEmpty())domainParts.add(Expr(listOf(listOf(Atom.Domain(Names(domain),origin=origin.ifEmpty {"domain"})))))
        for(key in listOf("domain_regex","domain_keyword"))if(node.has(key))domainParts.add(uncertain("${origin.ifEmpty {"domain"}}: $key не раскрыт"))
        if(node.has("ip_cidr")) {
            val ranges=strings(node,"ip_cidr").map(::cidr)
            domainParts.add(if(ranges.any {it==null})uncertain("Нераспознанная IP-сеть")else Expr(listOf(listOf(Atom.Ip(merge(ranges.filterNotNull()),origin=origin.ifEmpty {"IP"})))) )
        }
        // Destination domain and IP alternatives share the same OR group.
        if(domainParts.isNotEmpty())parts.add(either(domainParts))
        if(node.has("package_name"))parts.add(Expr(listOf(listOf(Atom.App(strings(node,"package_name").toSet(),origin=origin.ifEmpty {"app"})))))
        if(node.has("rule_set")) {
            val sets=strings(node,"rule_set").map {tag->
                if(tag in stack)uncertain("Циклическая ссылка набора")else {
                    val document=resolve(tag)
                    if(document==null)uncertain("${label(tag)}: данные не раскрыты (кэш, лимит или формат)")else {
                        val rows=document.optJSONArray("rules")
                        if(rows==null)uncertain("${label(tag)}: неизвестный формат")else either((0 until rows.length()).map {parse(rows.getJSONObject(it),label(tag),depth+1,stack+tag)})
                    }
                }
            }
            parts.add(either(sets))
        }
        val known=setOf("type","invert","domain","domain_suffix","domain_keyword","domain_regex","ip_cidr","package_name","rule_set")
        node.keys().forEach {key->if(key !in known)parts.add(uncertain("Условие $key требует ручной проверки"))}
        return parts.fold(yes,::both)
    }
    private fun cidr(value:String):Range?=runCatching {
        val pieces=value.split('/');require(pieces.size in 1..2)
        val address=requireNotNull(RouteRules.literal(pieces[0]));val bits=address.address.size*8
        val prefix=if(pieces.size==1)bits else pieces[1].toInt();require(prefix in 0..bits)
        val host=bits-prefix;val size=BigInteger.ONE.shiftLeft(host)
        val lo=BigInteger(1,address.address).shiftRight(host).shiftLeft(host)
        Range(bits,lo,lo+size-BigInteger.ONE)
    }.getOrNull()
    private fun merge(source:List<Range>):List<Range> {
        val result=mutableListOf<Range>()
        for(range in source.sortedWith(compareBy<Range> {it.bits}.thenBy {it.lo})) {
            step();val previous=result.lastOrNull()
            if(previous!=null&&previous.bits==range.bits&&range.lo<=previous.hi+BigInteger.ONE)result[result.lastIndex]=previous.copy(hi=maxOf(previous.hi,range.hi))else result.add(range)
        }
        return result
    }
    private fun intersect(left:List<Range>,right:List<Range>):List<Range> {
        val result=mutableListOf<Range>();var a=0;var b=0
        while(a<left.size&&b<right.size) {
            step();val x=left[a];val y=right[b]
            if(x.bits<y.bits){a++;continue};if(x.bits>y.bits){b++;continue}
            val lo=maxOf(x.lo,y.lo);val hi=minOf(x.hi,y.hi)
            if(lo<=hi)result.add(Range(x.bits,lo,hi))
            if(x.hi<y.hi)a++ else b++
        }
        return result
    }
    private fun subtract(source:List<Range>,excluded:List<Range>):List<Range> {
        val result=mutableListOf<Range>();var start=0
        for(range in source) {
            var lo=range.lo
            while(start<excluded.size&&(excluded[start].bits<range.bits||excluded[start].bits==range.bits&&excluded[start].hi<lo)){step();start++}
            var at=start
            while(at<excluded.size&&excluded[at].bits==range.bits&&excluded[at].lo<=range.hi) {
                step();val cut=excluded[at++]
                if(cut.lo>lo)result.add(Range(range.bits,lo,minOf(range.hi,cut.lo-BigInteger.ONE)))
                lo=maxOf(lo,cut.hi+BigInteger.ONE);if(lo>range.hi)break
            }
            if(lo<=range.hi)result.add(Range(range.bits,lo,range.hi))
        }
        return result
    }
    private fun domains(atoms:List<Atom.Domain>):Match {
        if(atoms.isEmpty())return Match(Kind.EXACT)
        val positive=atoms.filterNot {it.inverse}.map {it.names};val negative=atoms.filter {it.inverse}.map {it.names}
        val candidates=if(positive.isEmpty())sequenceOf(Pattern("mcrf-conflict.invalid",true))else positive.asSequence().flatMap {it.patterns.asSequence()}
        var uncertain=false
        for(pattern in candidates) {
            step(positive.size+negative.size+1)
            if(positive.all {it.contains(pattern.name)}&&negative.none {it.contains(pattern.name)})return Match(Kind.EXACT,"домен ${pattern.name}")
            if(pattern.suffix&&positive.all {it.suffixContains(pattern.name)}&&negative.none {it.suffixContains(pattern.name)}) {
                // An exact exclusion doesn't exclude the rest of a suffix. Never
                // conclude empty just because the finite witness search stopped.
                uncertain=true
                for(n in 0 until 64) {
                    step(negative.size+1);val name="mcrf-$n.${pattern.name}"
                    if(name.length<=253&&negative.none {it.contains(name)})return Match(Kind.EXACT,"домен $name")
                }
            }
        }
        return Match(if(uncertain||positive.isEmpty())Kind.POSSIBLE else Kind.EMPTY)
    }
    private fun ips(atoms:List<Atom.Ip>):Match {
        if(atoms.isEmpty())return Match(Kind.EXACT)
        var possible=listOf(32,128).map {Range(it,BigInteger.ZERO,BigInteger.ONE.shiftLeft(it)-BigInteger.ONE)}
        atoms.filterNot {it.inverse}.forEach {possible=intersect(possible,it.ranges)}
        possible=subtract(possible,merge(atoms.filter {it.inverse}.flatMap {it.ranges}))
        val range=possible.firstOrNull()?:return Match(Kind.EMPTY)
        val raw=range.lo.toByteArray();val bytes=ByteArray(range.bits/8)
        val count=minOf(raw.size,bytes.size);raw.copyInto(bytes,bytes.size-count,raw.size-count)
        return Match(Kind.EXACT,"IP ${java.net.InetAddress.getByAddress(bytes).hostAddress}")
    }
    private fun apps(atoms:List<Atom.App>):Match {
        if(atoms.isEmpty())return Match(Kind.EXACT)
        val positives=atoms.filterNot {it.inverse};val negative=atoms.filter {it.inverse}.flatMapTo(hashSetOf()){it.packages}
        if(positives.isEmpty())return Match(Kind.EXACT,"другое приложение")
        var candidates=positives.first().packages
        positives.drop(1).forEach {step(candidates.size);candidates=candidates.intersect(it.packages)}
        val app=candidates.firstOrNull {it !in negative}?:return Match(Kind.EMPTY)
        return Match(Kind.EXACT,"app:$app")
    }
    fun solve(expression:Expr):Match {
        var possible:Match?=null
        for(clause in expression.clauses) {
            step();val domain=clause.filterIsInstance<Atom.Domain>();val ip=clause.filterIsInstance<Atom.Ip>();val app=clause.filterIsInstance<Atom.App>()
            val checks=listOf(domains(domain),ips(ip),apps(app))
            if(checks.any {it.kind==Kind.EMPTY})continue
            val unknown=clause.filterIsInstance<Atom.Unknown>().map {it.reason}
            val mixed=domain.isNotEmpty()&&ip.isNotEmpty()
            val origins=(domain.map {it.origin}+ip.map {it.origin}+app.map {it.origin}).distinct()
            val explanation=(origins+checks.map {it.explanation}.filter {it.isNotEmpty()}+unknown+
                if(mixed)listOf("Совпадение домена и IP зависит от адреса назначения/DNS")else emptyList()).distinct().joinToString(" · ")
            val match=Match(if(unknown.isNotEmpty()||mixed||checks.any {it.kind==Kind.POSSIBLE})Kind.POSSIBLE else Kind.EXACT,explanation)
            if(match.kind==Kind.EXACT)return match
            possible=match
        }
        return possible?:Match(Kind.EMPTY)
    }
}
