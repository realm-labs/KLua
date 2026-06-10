package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object LuaPackageLibrary {
    private val directorySeparator: String = File.separator

    fun open(state: LuaState): LuaState {
        state.newTable()
        state.pushString("?.lua;?/init.lua")
        state.setField(-2, "path")
        state.pushString("$directorySeparator\n;\n?\n!\n-\n")
        state.setField(-2, "config")
        state.newTable()
        state.setField(-2, "loaded")
        state.newTable()
        state.setField(-2, "preload")
        setFunctionField(state, "searchpath", ::searchpath)
        setFunctionField(state, "_searcherResultType", ::searcherResultType)
        state.setGlobal("package")
        installLuaSource(state, REQUIRE_SOURCE, "stdlib-package.lua")
        return state
    }

    private fun searchpath(context: LuaCallContext): LuaReturn {
        val name = requiredString(context, 1, "package.searchpath")
        val path = requiredString(context, 2, "package.searchpath")
        val separator = optionalString(context, 3, ".")
        val replacement = optionalString(context, 4, directorySeparator)
        val normalizedName = if (separator.isEmpty()) {
            name
        } else {
            name.replace(separator, replacement)
        }
        val missingPaths = mutableListOf<String>()
        for (template in path.split(';')) {
            val candidate = template.replace("?", normalizedName)
            if (Files.isRegularFile(Path.of(candidate))) {
                return LuaReturn.of(candidate)
            }
            missingPaths += candidate
        }
        return LuaReturn.of(null, missingPaths.joinToString(separator = "\n\t") { candidate ->
            "no file '$candidate'"
        })
    }

    private fun searcherResultType(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(context.typeName(1))
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun optionalString(context: LuaCallContext, index: Int, default: String): String {
        return if (context.isNone(index) || context.isNil(index)) {
            default
        } else {
            context.toString(index)
                ?: throw LuaRuntimeException("bad argument #$index to 'package.searchpath' (string expected)")
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: LuaFunction) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private const val REQUIRE_SOURCE: String = """
        local searcherResultType = package._searcherResultType
        package._searcherResultType = nil

        package.searchers = {
            function(name)
                local loader = package.preload[name]
                if loader ~= nil then
                    return loader, ":preload:"
                end
                return nil, "\n\tno field package.preload['" .. name .. "']"
            end,

            function(name)
                local filename, searchError = package.searchpath(name, package.path)
                if filename == nil then
                    return nil, "\n\t" .. searchError
                end
                local loader, loadError = loadfile(filename)
                if loader == nil then
                    return nil, "\n\t" .. loadError
                end
                return loader, filename
            end,
        }

        function require(name)
            local nameType = searcherResultType(name)
            if nameType == "number" then
                name = name .. ""
            elseif nameType ~= "string" then
                error("bad argument #1 to 'require' (string expected)")
            end

            local loaded = package.loaded
            local value = loaded[name]
            if value ~= nil and value ~= false then
                return value
            end

            local errors = ""
            local searchers = package.searchers
            if searcherResultType(searchers) ~= "table" then
                error("'package.searchers' must be a table")
            end
            local index = 1
            while true do
                local searcher = searchers[index]
                if searcher == nil then
                    break
                end

                local loader, extra = searcher(name)
                local loaderType = searcherResultType(loader)
                if loaderType == "function" then
                    local result = loader(name, extra)
                    if result ~= nil then
                        loaded[name] = result
                    end
                    value = loaded[name]
                    if value == nil then
                        value = true
                        loaded[name] = value
                    end
                    return value, extra
                end
                if loaderType == "string" then
                    errors = errors .. loader
                elseif extra ~= nil then
                    errors = errors .. extra
                end
                index = index + 1
            end

            error("module '" .. name .. "' not found:" .. errors)
        end
    """
}
