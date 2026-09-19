package dev.veneranative.core.network

import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkFailureMapperTest {
    @Test
    fun mapsStableFailureCategories() {
        assertEquals(NetworkFailure.Timeout, NetworkFailureMapper.from(SocketTimeoutException(), false))
        assertEquals(NetworkFailure.Protocol, NetworkFailureMapper.from(ProtocolException(), false))
        assertEquals(NetworkFailure.Connection, NetworkFailureMapper.from(IOException(), false))
        assertEquals(NetworkFailure.Cancelled, NetworkFailureMapper.from(IOException(), true))
    }
}
