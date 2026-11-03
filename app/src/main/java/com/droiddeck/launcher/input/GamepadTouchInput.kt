package com.droiddeck.launcher.input

/** Negative codes are reserved for virtual-gamepad actions in the ScreenKeys editor. */
object GamepadTouchCodes {
    const val A = -1001; const val B = -1002; const val X = -1003; const val Y = -1004
    const val LB = -1005; const val RB = -1006; const val LT = -1007; const val RT = -1008
    const val SELECT = -1009; const val START = -1010; const val L3 = -1011; const val R3 = -1012
    const val UP = -1020; const val RIGHT = -1021; const val DOWN = -1022; const val LEFT = -1023
    const val LEFT_ANALOG = -1150; const val RIGHT_ANALOG = -1250
    const val LS_UP = -1100; const val LS_UP_RIGHT = -1101; const val LS_RIGHT = -1102; const val LS_DOWN_RIGHT = -1103
    const val LS_DOWN = -1104; const val LS_DOWN_LEFT = -1105; const val LS_LEFT = -1106; const val LS_UP_LEFT = -1107
    const val RS_UP = -1200; const val RS_UP_RIGHT = -1201; const val RS_RIGHT = -1202; const val RS_DOWN_RIGHT = -1203
    const val RS_DOWN = -1204; const val RS_DOWN_LEFT = -1205; const val RS_LEFT = -1206; const val RS_UP_LEFT = -1207

    val buttons = listOf(A, B, X, Y, LB, RB, LT, RT, SELECT, START, L3, R3)
    val dpad = listOf(UP, RIGHT, DOWN, LEFT)
    val leftStick = (LS_UP downTo LS_UP_LEFT).toList()
    val rightStick = (RS_UP downTo RS_UP_LEFT).toList()

    fun label(code: Int): String? = when (code) {
        A -> "A"; B -> "B"; X -> "X"; Y -> "Y"
        LB -> "L1"; RB -> "R1"; LT -> "L2"; RT -> "R2"
        SELECT -> "Select"; START -> "Start"; L3 -> "L3"; R3 -> "R3"
        UP -> "D-pad ↑"; RIGHT -> "D-pad →"; DOWN -> "D-pad ↓"; LEFT -> "D-pad ←"
        LEFT_ANALOG -> "Left analog stick"; RIGHT_ANALOG -> "Right analog stick"
        in leftStick -> "Left stick ${direction(LS_UP - code)}"
        in rightStick -> "Right stick ${direction(RS_UP - code)}"
        else -> null
    }

    private fun direction(index: Int) = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖").getOrElse(index) { "" }
    fun isGamepad(code: Int) = label(code) != null
}

/** Combines simultaneous touch contacts into one virtual controller report. */
class GamepadTouchInput {
    private val held = mutableSetOf<Int>()
    private var leftAnalog = 0f to 0f
    private var rightAnalog = 0f to 0f

    @Synchronized fun setAnalog(left: Boolean, x: Float, y: Float, bridge: PadBridge) {
        if (left) leftAnalog = x to y else rightAnalog = x to y
        bridge.applyTouch { state -> updateAxes(state) }
    }

    @Synchronized fun send(code: Int, down: Boolean, bridge: PadBridge): Boolean {
        if (!GamepadTouchCodes.isGamepad(code)) return false
        if (code == GamepadTouchCodes.LEFT_ANALOG || code == GamepadTouchCodes.RIGHT_ANALOG) return true
        if (down) held.add(code) else held.remove(code)
        bridge.applyTouch { state ->
            GamepadTouchCodes.buttons.forEach { button ->
                val id = when (button) {
                    GamepadTouchCodes.A -> PadState.A; GamepadTouchCodes.B -> PadState.B
                    GamepadTouchCodes.X -> PadState.X; GamepadTouchCodes.Y -> PadState.Y
                    GamepadTouchCodes.LB -> PadState.LB; GamepadTouchCodes.RB -> PadState.RB
                    GamepadTouchCodes.SELECT -> PadState.SELECT; GamepadTouchCodes.START -> PadState.START
                    GamepadTouchCodes.L3 -> PadState.L3; else -> PadState.R3
                }
                if (button == GamepadTouchCodes.LT) state.leftTrigger = if (button in held) 1f else 0f
                else if (button == GamepadTouchCodes.RT) state.rightTrigger = if (button in held) 1f else 0f
                else state.press(id, button in held)
            }
            state.up = GamepadTouchCodes.UP in held
            state.right = GamepadTouchCodes.RIGHT in held
            state.down = GamepadTouchCodes.DOWN in held
            state.left = GamepadTouchCodes.LEFT in held
            updateAxes(state)
        }
        return true
    }

    private fun updateAxes(state: PadState) {
        val left = combine(axis(GamepadTouchCodes.leftStick, held), leftAnalog)
        val right = combine(axis(GamepadTouchCodes.rightStick, held), rightAnalog)
        state.leftX = left.first; state.leftY = left.second
        state.rightX = right.first; state.rightY = right.second
    }

    private fun combine(a: Pair<Float, Float>, b: Pair<Float, Float>): Pair<Float, Float> {
        var x = a.first + b.first
        var y = a.second + b.second
        val magnitude = kotlin.math.sqrt(x * x + y * y)
        if (magnitude > 1f) { x /= magnitude; y /= magnitude }
        return x to y
    }

    private fun axis(codes: List<Int>, active: Set<Int>): Pair<Float, Float> {
        val vectors = arrayOf(
            0f to -1f, 0.7071f to -0.7071f, 1f to 0f, 0.7071f to 0.7071f,
            0f to 1f, -0.7071f to 0.7071f, -1f to 0f, -0.7071f to -0.7071f,
        )
        var x = 0f; var y = 0f
        codes.forEachIndexed { index, code -> if (code in active) { x += vectors[index].first; y += vectors[index].second } }
        val magnitude = kotlin.math.sqrt(x * x + y * y)
        if (magnitude > 1f) { x /= magnitude; y /= magnitude }
        return x to y
    }
}
