package io.github.realmlabs.klua.stdlib

import io.github.realmlabs.klua.api.LuaCallContext
import io.github.realmlabs.klua.api.LuaFunction
import io.github.realmlabs.klua.api.LuaReturn
import io.github.realmlabs.klua.api.LuaRuntimeException
import io.github.realmlabs.klua.api.LuaState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object LuaPackageLibrary {
    private val directorySeparator: String = File.separator
    private val isWindows: Boolean = File.separatorChar == '\\'
    internal val defaultLuaPath: String = if (isWindows) {
        """!\lua\?.lua;!\lua\?\init.lua;!\?.lua;!\?\init.lua;!\..\share\lua\5.5\?.lua;!\..\share\lua\5.5\?\init.lua;.\?.lua;.\?\init.lua"""
    } else {
        "/usr/local/share/lua/5.5/?.lua;/usr/local/share/lua/5.5/?/init.lua;" +
            "/usr/local/lib/lua/5.5/?.lua;/usr/local/lib/lua/5.5/?/init.lua;./?.lua;./?/init.lua"
    }
    internal val defaultCPath: String = if (isWindows) {
        """!\?.dll;!\..\lib\lua\5.5\?.dll;!\loadall.dll;.\?.dll"""
    } else {
        "/usr/local/lib/lua/5.5/?.so;/usr/local/lib/lua/5.5/loadall.so;./?.so"
    }

    fun open(
        state: LuaState,
        environment: (String) -> String? = ::readEnvironment,
        executableDirectory: String? = currentExecutableDirectory(),
    ): LuaState {
        val initialTop = state.getTop()
        state.pushRegistrySubtable(C_LIBRARIES_TABLE)
        state.pop()
        val luaPath = resolvePackagePath(state, "LUA_PATH", defaultLuaPath, environment, executableDirectory)
        val cPath = resolvePackagePath(state, "LUA_CPATH", defaultCPath, environment, executableDirectory)

        state.pushRegistrySubtable(LOADED_TABLE)
        val loadedIndex = state.getTop()
        state.pushRegistrySubtable(PRELOAD_TABLE)
        val preloadIndex = state.getTop()
        state.newTable()
        state.pushString(luaPath)
        state.setField(-2, "path")
        state.pushString(cPath)
        state.setField(-2, "cpath")
        state.pushString("$directorySeparator\n;\n?\n!\n-\n")
        state.setField(-2, "config")
        state.pushValue(loadedIndex)
        state.setField(-2, "loaded")
        state.pushValue(preloadIndex)
        state.setField(-2, "preload")
        setFunctionField(state, "loadlib", ::loadlib)
        setFunctionField(state, "searchpath", ::searchpath)
        setFunctionField(state, "_loadfile") { context -> loadfile(context, state) }
        setFunctionField(state, "_rawget", ::rawget)
        setFunctionField(state, "_searcherResultType", ::searcherResultType)
        setFunctionField(state, "_cString", ::cString)
        setFunctionField(state, "_moduleRoot", ::moduleRoot)
        state.setGlobal("package")
        state.setTop(initialTop)
        installLuaSource(state, REQUIRE_SOURCE, "stdlib-package.lua")
        return state
    }

    internal fun resolvePackagePath(
        environmentName: String,
        defaultPath: String,
        environmentEnabled: Boolean,
        environment: (String) -> String? = ::readEnvironment,
        executableDirectory: String? = currentExecutableDirectory(),
    ): String {
        val configured = if (environmentEnabled) {
            configuredPackagePath(environmentName, environment)
        } else {
            null
        }
        return finishPackagePath(configured, defaultPath, executableDirectory)
    }

    private fun resolvePackagePath(
        state: LuaState,
        environmentName: String,
        defaultPath: String,
        environment: (String) -> String?,
        executableDirectory: String?,
    ): String {
        val configured = if (state.config.packagePathEnvironmentEnabled) {
            configuredPackagePath(environmentName, environment)
        } else {
            null
        }
        if (configured == null) {
            return finishPackagePath(null, defaultPath, executableDirectory)
        }
        val initialTop = state.getTop()
        state.pushRegistryTable()
        state.getField(-1, "LUA_NOENV")
        val noEnvironment = state.toBoolean(-1)
        state.setTop(initialTop)
        return finishPackagePath(if (noEnvironment) null else configured, defaultPath, executableDirectory)
    }

    private fun configuredPackagePath(
        environmentName: String,
        environment: (String) -> String?,
    ): String? {
        return environment("${environmentName}_5_5") ?: environment(environmentName)
    }

    private fun finishPackagePath(
        configured: String?,
        defaultPath: String,
        executableDirectory: String?,
    ): String {
        val selected = configured?.let { expandDefaultPath(it, defaultPath) } ?: defaultPath
        if (!isWindows || '!' !in selected) {
            return selected
        }
        val directory = executableDirectory
            ?: throw LuaRuntimeException("unable to get executable directory")
        return selected.replace("!", directory)
    }

    private fun expandDefaultPath(path: String, defaultPath: String): String {
        val marker = path.indexOf(";;")
        if (marker < 0) {
            return path
        }
        val prefix = path.substring(0, marker)
        val suffix = path.substring(marker + 2)
        return buildString {
            if (prefix.isNotEmpty()) {
                append(prefix)
                append(';')
            }
            append(defaultPath)
            if (suffix.isNotEmpty()) {
                append(';')
                append(suffix)
            }
        }
    }

    private fun readEnvironment(name: String): String? {
        return try {
            System.getenv(name)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun currentExecutableDirectory(): String? {
        return try {
            ProcessHandle.current().info().command().orElse(null)
                ?.let(Path::of)
                ?.parent
                ?.toString()
        } catch (_: InvalidPathException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun loadlib(context: LuaCallContext): LuaReturn {
        requiredString(context, 1, "loadlib").substringBefore('\u0000')
        requiredString(context, 2, "loadlib").substringBefore('\u0000')
        return LuaReturn.of(null, DYNAMIC_LIBRARIES_DISABLED_MESSAGE, "absent")
    }

    private fun loadfile(context: LuaCallContext, state: LuaState): LuaReturn {
        val filename = requiredString(context, 1, "loadfile")
        val bytes = try {
            Files.readAllBytes(Path.of(filename))
        } catch (error: IOException) {
            return LuaReturn.of(null, error.message ?: "cannot read file '$filename'")
        }
        val source = loadFileContent(bytes)
        return if (isKLuaBinaryChunk(source.bytes)) {
            context.loadBytecode(source.bytes, "@$filename")
        } else {
            context.load(source.source, "@$filename", null, environmentProvided = false)
        }
    }

    private fun searchpath(context: LuaCallContext): LuaReturn {
        val name = requiredString(context, 1, "searchpath").substringBefore('\u0000')
        val path = requiredString(context, 2, "searchpath").substringBefore('\u0000')
        val separator = optionalString(context, 3, ".", "searchpath").substringBefore('\u0000')
        val replacement = optionalString(context, 4, directorySeparator, "searchpath").substringBefore('\u0000')
        val normalizedName = if (separator.isEmpty()) {
            name
        } else {
            name.replace(separator, replacement)
        }
        val expandedPath = path.replace("?", normalizedName)
        val missingPaths = mutableListOf<String>()
        for (candidate in searchPathTemplates(expandedPath)) {
            if (candidate.isNotEmpty() && isReadablePath(candidate)) {
                return LuaReturn.of(candidate)
            }
            missingPaths += candidate
        }
        return LuaReturn.of(null, missingPaths.joinToString(separator = "\n\t") { candidate ->
            "no file '$candidate'"
        })
    }

    private fun isReadablePath(candidate: String): Boolean {
        return try {
            Files.newInputStream(Path.of(candidate)).use { }
            true
        } catch (_: IOException) {
            false
        } catch (_: InvalidPathException) {
            false
        } catch (_: SecurityException) {
            false
        }
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

    private fun cString(context: LuaCallContext): LuaReturn {
        return LuaReturn.of(requiredString(context, 1, "require").substringBefore('\u0000'))
    }

    private fun isKLuaBinaryChunk(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'K'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'u'.code.toByte() &&
            bytes[3] == 'a'.code.toByte()
    }

    private fun moduleRoot(context: LuaCallContext): LuaReturn {
        val name = requiredString(context, 1, "require").substringBefore('\u0000')
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
    private const val C_LIBRARIES_TABLE = "_CLIBS"
    private const val LOADED_TABLE = "_LOADED"
    private const val PRELOAD_TABLE = "_PRELOAD"

    private const val REQUIRE_SOURCE: String = """
        local package = package
        local searcherResultType = package._searcherResultType
        local moduleRoot = package._moduleRoot
        local packageLoadfile = package._loadfile
        local searcherRawGet = package._rawget
        local cString = package._cString
        local loadedTable = package.loaded
        local preloadTable = package.preload
        package._searcherResultType = nil
        package._moduleRoot = nil
        package._loadfile = nil
        package._rawget = nil
        package._cString = nil

        local function packagePathString(value, field)
            local valueType = searcherResultType(value)
            if valueType == "string" then
                return value
            elseif valueType == "number" then
                return value .. ""
            end
            error("'package." .. field .. "' must be a string", 0)
        end

        local function nativeOpenName(name)
            return "luaopen_" .. string.gsub(name, "%.", "_")
        end

        local function loadNative(filename, name)
            local normalized = string.gsub(name, "%.", "_")
            local mark = string.find(normalized, "-", 1, true)
            if mark ~= nil then
                local loader, loadError, where = package.loadlib(
                    filename,
                    "luaopen_" .. string.sub(normalized, 1, mark - 1)
                )
                if loader ~= nil or where ~= "init" then
                    return loader, loadError, where
                end
                return package.loadlib(filename, "luaopen_" .. string.sub(normalized, mark + 1))
            end
            return package.loadlib(filename, nativeOpenName(name))
        end

        package.searchers = {
            function(name)
                name = cString(name)
                local loader = preloadTable[name]
                if loader ~= nil then
                    return loader, ":preload:"
                end
                return "no field package.preload['" .. name .. "']"
            end,

            function(name)
                name = cString(name)
                local path = packagePathString(package.path, "path")
                local filename, searchError = package.searchpath(name, path)
                if filename == nil then
                    return searchError
                end
                local loader, loadError = packageLoadfile(filename)
                if loader == nil then
                    error("error loading module '" .. name .. "' from file '" .. filename .. "':\n\t" .. loadError, 0)
                end
                return loader, filename
            end,

            function(name)
                name = cString(name)
                local cpath = packagePathString(package.cpath, "cpath")
                local filename, searchError = package.searchpath(name, cpath)
                if filename == nil then
                    return searchError
                end
                local loader, loadError = loadNative(filename, name)
                if loader == nil then
                    error("error loading module '" .. name .. "' from file '" .. filename .. "':\n\t" .. loadError)
                end
                return loader, filename
            end,

            function(name)
                name = cString(name)
                local rootName = moduleRoot(name)
                if rootName == nil then
                    return
                end
                local cpath = packagePathString(package.cpath, "cpath")
                local filename, searchError = package.searchpath(rootName, cpath)
                if filename == nil then
                    return searchError
                end
                local loader, loadError, where = loadNative(filename, name)
                if loader ~= nil then
                    return loader, filename
                end
                if where ~= "init" then
                    error("error loading module '" .. name .. "' from file '" .. filename .. "':\n\t" .. loadError)
                end
                return "no module '" .. name .. "' in file '" .. filename .. "'"
            end,
        }

        function require(name)
            local nameType = searcherResultType(name)
            if nameType == "number" then
                name = name .. ""
            elseif nameType ~= "string" then
                error("bad argument #1 to 'require' (string expected)", 2)
            end

            local loaderName = name
            name = cString(name)

            local loaded = loadedTable
            local value = loaded[name]
            if value ~= nil and value ~= false then
                return value
            end

            local errors = ""
            local searchers = package.searchers
            if searcherResultType(searchers) ~= "table" then
                error("'package.searchers' must be a table", 2)
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
                    local result = loader(loaderName, extra)
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
                if loaderType == "string" or loaderType == "number" then
                    errors = errors .. "\n\t" .. loader
                end
                index = index + 1
            end

            error("module '" .. name .. "' not found:" .. errors, 2)
        end
    """
}
