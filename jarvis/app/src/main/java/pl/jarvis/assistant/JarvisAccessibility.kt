package pl.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Sterowanie ekranem (usługa ułatwień dostępu): Jarvis czyta, co jest na ekranie, wpisuje tekst
 * w pola formularzy i naciska przyciski – wyłącznie na polecenie z narzędzi AI.
 */
class JarvisAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Okno aplikacji na wierzchu – z pominięciem samego Jarvisa (mówimy do niego „w tle”). */
    private fun targetRoot(pkg: String? = null): AccessibilityNodeInfo? {
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
        return roots.firstOrNull { if (pkg != null) it.packageName == pkg else it.packageName != packageName }
            ?: rootInActiveWindow
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }

    fun readScreen(): String {
        val root = targetRoot() ?: return "Nie widzę ekranu."
        val lines = mutableListOf("Aplikacja: ${appLabel(root.packageName?.toString())}")
        walk(root) { n ->
            if (!n.isVisibleToUser || lines.size > 250) return@walk
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            when {
                n.isEditable -> lines += "[pole „${label(n)}”: ${if (n.isShowingHintText) "" else text}]"
                text.isNotEmpty() -> lines += if (n.isClickable) "[przycisk] $text" else text
                desc.isNotEmpty() && n.isClickable -> lines += "[przycisk] $desc"
            }
        }
        return lines.distinct().joinToString("\n").take(6000)
    }

    fun fill(field: String, value: String): String {
        val root = targetRoot() ?: return "Nie widzę ekranu."
        val fields = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { if (it.isEditable && it.isVisibleToUser) fields += it }
        if (fields.isEmpty()) return "Na ekranie nie ma pól do wpisania."
        val q = PhoneTools.normalize(field)
        val target = when {
            q.isEmpty() -> root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: fields.firstOrNull { it.isShowingHintText || it.text.isNullOrEmpty() } ?: fields.first()
            else -> fields.firstOrNull { PhoneTools.normalize(label(it)) == q }
                ?: fields.firstOrNull { PhoneTools.normalize(label(it)).contains(q) || q.contains(PhoneTools.normalize(label(it)).ifEmpty { "\u0000" }) }
        } ?: return "Nie ma pola „$field”. Pola na ekranie: ${fields.joinToString { "„${label(it)}”" }}."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Wpisano w pole „${label(target)}”."
        else "Nie udało się wpisać w pole „${label(target)}”."
    }

    fun click(text: String, system: String): String {
        val global = when (PhoneTools.normalize(system)) {
            "" -> null
            "wstecz" -> GLOBAL_ACTION_BACK
            "ekran glowny", "ekran_glowny" -> GLOBAL_ACTION_HOME
            "ostatnie" -> GLOBAL_ACTION_RECENTS
            "powiadomienia" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Przyciski systemowe: wstecz, ekran_glowny, ostatnie, powiadomienia."
        }
        if (global != null) return if (performGlobalAction(global)) "Naciśnięto: $system." else "Nie udało się."
        val root = targetRoot() ?: return "Nie widzę ekranu."
        val q = PhoneTools.normalize(text)
        if (q.isEmpty()) return "Błąd: co nacisnąć?"
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { if (it.isVisibleToUser) nodes += it }
        fun name(n: AccessibilityNodeInfo) = PhoneTools.normalize("${n.text ?: ""} ${n.contentDescription ?: ""}")
        val node = nodes.firstOrNull { name(it) == q } ?: nodes.firstOrNull { name(it).contains(q) }
            ?: return "Nie widzę na ekranie „$text”."
        return if (clickNode(node)) "Naciśnięto „$text”." else "Nie udało się nacisnąć „$text”."
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Podpis pola: podpowiedź, opis, etykieta albo identyfikator widoku. */
    private fun label(n: AccessibilityNodeInfo): String =
        n.hintText?.toString()?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: n.labeledBy?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' ')
            ?: ""

    private fun appLabel(pkg: String?): String = pkg?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
    } ?: "?"

    companion object {
        @Volatile
        var instance: JarvisAccessibility? = null
            private set

        /** Po otwarciu czatu z gotowym tekstem naciska „wyślij” (czeka najwyżej 10 s, aż czat się pokaże). */
        fun pressSendWhenReady(pkg: String) {
            Thread {
                repeat(33) {
                    Thread.sleep(300)
                    val service = instance ?: return@Thread
                    val root = service.targetRoot(pkg) ?: return@repeat
                    if (root.packageName != pkg) return@repeat
                    val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
                        ?: run {
                            var found: AccessibilityNodeInfo? = null
                            service.walk(root) { n ->
                                val d = PhoneTools.normalize(n.contentDescription?.toString().orEmpty())
                                if (found == null && (d == "wyslij" || d == "send")) found = n
                            }
                            found
                        }
                    if (send != null && service.clickNode(send)) {
                        PhoneTools.log("wysłano w $pkg")
                        return@Thread
                    }
                }
                PhoneTools.log("nie znalazłem przycisku wyślij w $pkg")
            }.start()
        }
    }
}
