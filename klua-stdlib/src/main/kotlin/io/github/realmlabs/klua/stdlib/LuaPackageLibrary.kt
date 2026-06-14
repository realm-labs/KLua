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
        state.pushString("?.so")
        state.setField(-2, "cpath")
        state.pushString("$directorySeparator\n;\n?\n!\n-\n")
        state.setField(-2, "config")
        state.newTable()
        state.setField(-2, "loaded")
        state.newTable()
        state.setField(-2, "preload")
        setFunctionField(state, "loadlib", ::loadlib)
        setFunctionField(state, "searchpath", ::searchpath)
        setFunctionField(state, "_rawget", ::rawget)
        setFunctionField(state, "_searcherResultType", ::searcherResultType)
        setFunctionField(state, "_moduleRoot", ::moduleRoot)
        state.setGlobal("package")
        installLuaSource(state, REQUIRE_SOURCE, "stdlib-package.lua")
        return state
    }

    private fun loadlib(context: LuaCallContext): LuaReturn {
        requiredString(context, 1, "loadlib")
        requiredString(context, 2, "loadlib")
        return LuaReturn.of(null, DYNAMIC_LIBRARIES_DISABLED_MESSAGE, "absent")
    }

    private fun searchpath(context: LuaCallContext): LuaReturn {
        val name = requiredString(context, 1, "searchpath")
        val path = requiredString(context, 2, "searchpath")
        val separator = optionalString(context, 3, ".", "searchpath")
        val replacement = optionalString(context, 4, directorySeparator, "searchpath")
        val normalizedName = if (separator.isEmpty()) {
            name
        } else {
            name.replace(separator, replacement)
        }
        val missingPaths = mutableListOf<String>()
        for (template in searchPathTemplates(path)) {
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

    private fun searchPathTemplates(path: String): List<String> {
        val templates = mutableListOf<String>()
        var start = 0
        for (index in path.indices) {
            if (path[index] == ';') {
                templates += path.substring(start, index)
                start = index + 1
            }
        }
        templates += path.substring(start)
        return templates
    }

    private fun searcherResultType(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(context.typeName(1))
    }

    private fun rawget(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(context.getTableValue(1, context.get(2)))
    }

    private fun moduleRoot(context: LuaCallContext): LuaReturn {
        val name = requiredString(context, 1, "require")
        val index = name.indexOf('.')
        return if (index < 0) {
            LuaReturn.of(null)
        } else {
            LuaReturn.of(name.substring(0, index))
        }
    }

    private fun requiredString(context: LuaCallContext, index: Int, functionName: String): String {
        return context.toString(index)
            ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
    }

    private fun optionalString(context: LuaCallContext, index: Int, default: String, functionName: String): String {
        return if (context.isNone(index) || context.isNil(index)) {
            default
        } else {
            context.toString(index)
                ?: throw LuaRuntimeException("bad argument #$index to '$functionName' (string expected)")
        }
    }

    private fun setFunctionField(state: LuaState, name: String, function: LuaFunction) {
        state.pushFunction(function)
        state.setField(-2, name)
    }

    private const val DYNAMIC_LIBRARIES_DISABLED_MESSAGE = "dynamic libraries not enabled; check your Lua installation"

    private const val REQUIRE_SOURCE: String = """
        local searcherResultType = package._searcherResultType
        local moduleRoot = package._moduleRoot
        local searcherRawGet = package._rawget
        local loadedTable = package.loaded
        local preloadTable = package.preload
        package._searcherResultType = nil
        package._moduleRoot = nil
        package._rawget = nil

        package.searchers = {
            function(name)
                local loader = preloadTable[name]
                if loader ~= nil then
                    return loader, ":preload:"
                end
                return "\n\tno field package.preload['" .. name .. "']"
            end,

            function(name)
                if searcherResultType(package.path) ~= "string" then
                    error("'package.path' must be a string", 0)
                end
                local filename, searchError = package.searchpath(name, package.path)
                if filename == nil then
                    return "\n\t" .. searchError
                end
                local loader, loadError = loadfile(filename)
                if loader == nil then
                    error("error loading module '" .. name .. "' from file '" .. filename .. "':\n\t" .. loadError, 0)
                end
                return loader, filename
            end,

            function(name)
                if searcherResultType(package.cpath) ~= "string" then
                    error("'package.cpath' must be a string", 0)
                end
                local filename, searchError = package.searchpath(name, package.cpath)
                if filename == nil then
                    return "\n\t" .. searchError
                end
                error("error loading module '" .. name .. "' from file '" .. filename .. "':\n\t" ..
                    "$DYNAMIC_LIBRARIES_DISABLED_MESSAGE", 0)
            end,

            function(name)
                local rootName = moduleRoot(name)
                if rootName == nil then
                    return
                end
                if searcherResultType(package.cpath) ~= "string" then
                    error("'package.cpath' must be a string", 0)
                end
                local filename, searchError = package.searchpath(rootName, package.cpath)
                if filename == nil then
                    return "\n\t" .. searchError
                end
                return "\n\tno module '" .. name .. "' in file '" .. filename .. "'"
            end,
        }

        function require(name)
            local nameType = searcherResultType(name)
            if nameType == "number" then
                name = name .. ""
            elseif nameType ~= "string" then
                error("bad argument #1 to 'require' (string expected)", 0)
            end

            local loaded = loadedTable
            local value = loaded[name]
            if value ~= nil and value ~= false then
                return value
            end

            local errors = ""
            local searchers = package.searchers
            if searcherResultType(searchers) ~= "table" then
                error("'package.searchers' must be a table", 0)
            end
            local index = 1
            while true do
                local searcher = searcherRawGet(searchers, index)
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
                end
                index = index + 1
            end

            error("module '" .. name .. "' not found:" .. errors, 0)
        end
    """
}
