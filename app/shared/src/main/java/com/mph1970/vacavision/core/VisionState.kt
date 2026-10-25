package com.mph1970.vacavision.core

/** Native read-only UI state. Audio resources are owned by the external Crown service. */
object VisionState {
    @Volatile var role = VisionRole.STANDARD_DISPLAY
    @Volatile var crown: String = CrownStatusClient.offline().toString()
}
