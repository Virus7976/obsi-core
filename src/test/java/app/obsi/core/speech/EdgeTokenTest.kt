package app.obsi.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** The answers come from edge-tts's own drm.py (generate_sec_ms_gec_v2), run in Python. */
class EdgeTokenTest {
    @Test fun matchesEdgeTts() {
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", EdgeVoices.Token.at(1700000000.0))
        assertEquals("92B7FB55A2CA83ED-71842240C80DE51D944907D8E73F902DC4DADD252E67011C0D831748A0336A73", EdgeVoices.Token.at(1763900000.5))
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", EdgeVoices.Token.at(1800000123.25))
    }
}
