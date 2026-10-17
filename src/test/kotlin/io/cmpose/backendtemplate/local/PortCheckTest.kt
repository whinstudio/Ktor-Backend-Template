package io.cmpose.backendtemplate.local

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortCheckTest {
    @Test fun `a port held by another socket is reported busy`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { held ->
            assertFalse(PortCheck.isFree(held.localPort))
        }
    }

    @Test fun `a released port is free`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertTrue(PortCheck.isFree(port))
    }
}
