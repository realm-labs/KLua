package io.github.realmlabs.klua.api

import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaApiBoundaryTest {
    @Test
    fun `java callable API does not expose core or Kotlin function types`() {
        val forbidden = exportedClasses().flatMap { type ->
            buildList {
                checkType(type, "superclass", type.superclass)?.let(::add)
                type.interfaces.forEach { interfaceType ->
                    checkType(type, "interface", interfaceType)?.let(::add)
                }
                type.declaredConstructors
                    .filter { constructor -> constructor.isJavaCallable() }
                    .forEach { constructor ->
                        constructor.parameterTypes.forEach { parameter ->
                            checkType(constructor, "parameter", parameter)?.let(::add)
                        }
                    }
                type.declaredMethods
                    .filter { method -> method.isJavaCallable() }
                    .forEach { method ->
                        checkType(method, "return", method.returnType)?.let(::add)
                        method.parameterTypes.forEach { parameter ->
                            checkType(method, "parameter", parameter)?.let(::add)
                        }
                    }
                type.declaredFields
                    .filter { field -> field.isJavaCallable() }
                    .forEach { field -> checkType(field, "field", field.type)?.let(::add) }
            }
        }.sorted()

        assertEquals(emptyList(), forbidden)
    }

    private fun exportedClasses(): List<Class<*>> {
        val packagePath = API_PACKAGE.replace('.', '/')
        val classesRoot = checkNotNull(Lua::class.java.protectionDomain.codeSource).location.toURI().let {
            java.nio.file.Path.of(it)
        }
        return Files.walk(classesRoot.resolve(packagePath)).use { paths ->
            paths.filter { path -> path.isRegularFile() && path.extension == "class" }
                .map { path ->
                    val binaryName = path.relativeTo(classesRoot)
                        .invariantSeparatorsPathString
                        .removeSuffix(".class")
                        .replace('/', '.')
                    Class.forName(binaryName, false, Lua::class.java.classLoader)
                }
                .filter { type -> type.isExternallyAccessible() && !type.isSynthetic }
                .toList()
        }
    }

    private fun Class<*>.isExternallyAccessible(): Boolean {
        var type: Class<*>? = this
        while (type != null) {
            if (!Modifier.isPublic(type.modifiers)) {
                return false
            }
            type = type.enclosingClass
        }
        return true
    }

    private fun Member.isJavaCallable(): Boolean {
        return (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) && !isSynthetic
    }

    private fun checkType(owner: Any, position: String, candidate: Class<*>?): String? {
        var type = candidate ?: return null
        while (type.isArray) {
            type = type.componentType
        }
        return if (FORBIDDEN_TYPE_PREFIXES.any(type.name::startsWith)) {
            "$owner exposes ${type.name} as $position"
        } else {
            null
        }
    }

    private companion object {
        private const val API_PACKAGE = "io.github.realmlabs.klua.api"
        private val FORBIDDEN_TYPE_PREFIXES = listOf(
            "io.github.realmlabs.klua.core.",
            "kotlin.jvm.functions.",
            "kotlin.reflect.",
            "kotlin.sequences.",
            "kotlinx.coroutines.",
        )
    }
}
