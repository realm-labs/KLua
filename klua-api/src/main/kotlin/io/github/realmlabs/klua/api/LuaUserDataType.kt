package io.github.realmlabs.klua.api

class LuaUserDataType<T : Any> private constructor(
    private val registerMethod: (String, LuaUserDataMethod<T>) -> Unit,
    private val registerProperty: (String, LuaUserDataGetter<T>?, LuaUserDataSetter<T>?) -> Unit,
) {
    internal object Factory {
        @JvmSynthetic
        internal fun <T : Any> create(
            registerMethod: (String, LuaUserDataMethod<T>) -> Unit,
            registerProperty: (String, LuaUserDataGetter<T>?, LuaUserDataSetter<T>?) -> Unit,
        ): LuaUserDataType<T> {
            return LuaUserDataType(registerMethod, registerProperty)
        }
    }

    fun method(name: String, method: LuaUserDataMethod<T>) {
        require(name.isNotBlank()) { "method name must not be blank" }
        registerMethod(name, method)
    }

    fun property(name: String, getter: LuaUserDataGetter<T>) {
        property(name, getter, null)
    }

    fun property(name: String, getter: LuaUserDataGetter<T>?, setter: LuaUserDataSetter<T>?) {
        require(name.isNotBlank()) { "property name must not be blank" }
        require(getter != null || setter != null) { "property must have a getter or setter" }
        registerProperty(name, getter, setter)
    }
}
