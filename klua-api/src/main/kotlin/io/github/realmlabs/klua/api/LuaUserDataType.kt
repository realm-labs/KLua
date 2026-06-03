package io.github.realmlabs.klua.api

class LuaUserDataType<T : Any> internal constructor(
    private val registerMethod: (String, LuaUserDataMethod<T>) -> Unit,
    private val registerProperty: (String, LuaUserDataGetter<T>?, LuaUserDataSetter<T>?) -> Unit,
) {
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
