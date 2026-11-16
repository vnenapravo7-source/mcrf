package org.mcrf.client

import kotlin.math.abs

/** One pointer, fixed hit geometry for the entire gesture (including expanding tabs).
 * Rejected streams stay rejected until the next DOWN; velocity never commits a tab. */
internal class BottomNavigationDrag(private val slop: Float) {
    enum class State { IDLE, PENDING, DRAGGING, REJECTED }
    var state = State.IDLE; private set
    private var slots = emptyList<BottomNavigationGeometry.Slot>()
    val centers get() = slots.map { it.center }
    var x = 0f; private set
    private var hitX = 0f
    private var downX = 0f
    private var downY = 0f
    private var width = 0f
    private var height = 0f
    fun begin(x: Float, y: Float, width: Float, height: Float, slots: List<BottomNavigationGeometry.Slot>) {
        reset()
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
            slots.isEmpty() || slots.any { !it.left.isFinite() || !it.width.isFinite() || it.width <= 0f } ||
            !x.isFinite() || !y.isFinite() || x < 0 || x >= width || y < 0 || y >= height) return
        this.slots = slots.toList(); this.width = width; this.height = height
        downX = x; downY = y; this.x = x; hitX = x; state = State.PENDING
    }
    fun move(x: Float, y: Float) {
        if (state != State.PENDING && state != State.DRAGGING) return
        val dx = abs(x - downX); val dy = abs(y - downY)
        if (!x.isFinite() || !y.isFinite() || x < 0 || x >= width || y < 0 || y >= height ||
            (dy > slop && dy >= dx)) { reject(); return }
        if (state == State.PENDING && dx > slop && dx > dy) state = State.DRAGGING
        hitX = x
        this.x = x.coerceIn(centers.min(), centers.max())
    }
    fun reject() { if (state != State.IDLE) state = State.REJECTED }
    fun reset() { state = State.IDLE; slots = emptyList() }
    /** Consume the stream before invoking navigation, which may synchronously rebind the view. */
    fun finish(): Int? = destination().also { reset() }
    fun destination(): Int? = if (state == State.DRAGGING) slots.indices.firstOrNull {
        hitX >= slots[it].left && hitX < slots[it].left + slots[it].width
    } else null
    fun weights(): List<Float> {
        return BottomNavigationGeometry.weightsAt(slots, x)
    }
}
