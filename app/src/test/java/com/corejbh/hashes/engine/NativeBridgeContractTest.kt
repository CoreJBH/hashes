package com.corejbh.hashes.engine

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin declarations and the C++ exports have to agree exactly: a mismatch is
 * either an `UnsatisfiedLinkError` at the first call or, worse, a call that links
 * to the wrong signature. This test reads `jni_bridge.cpp` and compares its
 * `Java_com_corejbh_hashes_engine_NativeMiner_...` symbols with the external
 * methods [NativeMiner] declares.
 */
class NativeBridgeContractTest {

    private val bridgeSource = File("src/main/cpp/jni_bridge.cpp")

    private fun declaredNatives(): Map<String, Class<*>> =
        NativeMiner::class.java.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .associate { it.name to it.returnType }

    @Test
    fun everyDeclaredNativeHasASymbolAndEverySymbolADeclaration() {
        assumeTrue("jni_bridge.cpp is not next to the test run", bridgeSource.isFile)
        val exported = Regex("Java_com_corejbh_hashes_engine_NativeMiner_(\\w+)")
            .findAll(bridgeSource.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(declaredNatives().keys, exported)
    }

    @Test
    fun theReturnTypesMatchTheNativeSignatures() {
        val natives = declaredNatives()

        assertEquals(Boolean::class.javaPrimitiveType, natives["nativePollBestHash"])
        assertEquals(Long::class.javaPrimitiveType, natives["nativePollFoundHash"])
        assertEquals(Void.TYPE, natives["nativeSetThreadCap"])
        assertEquals(Void.TYPE, natives["nativeSetTurboMode"])
        assertEquals(String::class.java, natives["nativeVersion"])
    }

    @Test
    fun theThreadCapTakesOneInt() {
        val method = NativeMiner::class.java.declaredMethods
            .first { it.name == "nativeSetThreadCap" }

        assertEquals(listOf(Int::class.javaPrimitiveType), method.parameterTypes.toList())
        assertTrue(Modifier.isNative(method.modifiers))
    }
}
