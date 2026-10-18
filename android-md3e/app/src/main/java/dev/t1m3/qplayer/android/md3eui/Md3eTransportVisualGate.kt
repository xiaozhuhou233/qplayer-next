package dev.t1m3.qplayer.android.md3eui

/** Main-thread ownership: one disposed/outgoing player cannot release another. */
internal class TransportVisualGate {
    private val owners = mutableSetOf<Any>()
    val active: Boolean get() = owners.isNotEmpty()

    fun setActive(owner: Any, active: Boolean): Boolean {
        val wasActive = this.active
        if (active) owners.add(owner) else owners.remove(owner)
        return wasActive != this.active
    }
}
