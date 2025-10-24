package com.mb.mbplugin.karate.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.mb.mbplugin.karate.psi.KarateTokenTypes

class KarateLexer : LexerBase() {
    private var buffer: CharSequence? = null
    private var startOffset = 0
    private var endOffset = 0
    private var position = 0
    private var tokenType: IElementType? = null
    private var tokenStart = 0
    private var tokenEnd = 0
    private var isInTableRow = false
    private var isFirstTableRow = false
    private val tableHeaderNames = mutableListOf<String>()

    companion object {
        private val KARATE_DSL_KEYWORDS = setOf(
            "def", "text", "eval", "match", "assert", "call", "read", "set", "remove", "replace",
            "configure", "callonce", "print", "get", "copy", "table", "yaml", "csv", "string",
            "json", "xml", "xmlstring", "bytes", "listen", "doc", "param", "header", "cookie",
            "form", "multipart", "params", "headers", "cookies", "retry", "soap",
            "contains", "only", "deep", "schema", "type", "each", "js", "graphql", "if", "else", "compare",
            "delay", "repeat", "karateEnv", "karateTags", "driver", "robot", "report"
        )
        
        private val HTTP_KEYWORDS = setOf(
            "url", "path", "method", "status", "request", "response", "responseBytes", 
            "responseStatus", "responseHeaders", "responseCookies", "responseTime", 
            "responseType", "requestTimeStamp", "get", "post", "put", "delete", "patch", "head", "options",
            "connect", "trace", "http", "https", "ws", "wss", "graphql", "soap", "multipart", "form"
        )
        
        private val GHERKIN_KEYWORDS = setOf(
            "feature", "background", "scenario", "examples", "rule", "example"
        )
        
        private val STEP_KEYWORDS = setOf("given", "when", "then", "and", "but")
        
        private val ACTION_KEYWORDS = setOf(
            "match", "assert", "print", "call", "read", "set", "remove", "replace", "eval"
        )
    }

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.position = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer ?: ""
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (position >= endOffset) {
            tokenType = null
            return
        }

        tokenStart = position
        val startPosition = position
        
        when {
            isAtEnd() -> tokenType = null
            currentChar().isWhitespace() -> readWhitespace()
            currentChar() == '#' -> readComment()
            currentChar() == '@' -> readTag()
            currentChar() == '|' -> readPipe()
            currentChar() == '*' -> readSingleChar(KarateTokenTypes.ASTERISK)
            currentChar() == ':' -> readSingleChar(KarateTokenTypes.COLON)
            isDocStringStart() -> readDocString()
            currentChar() == '"' -> readQuotedString('"', KarateTokenTypes.DOUBLE_QUOTED_STRING)
            currentChar() == '\'' -> readQuotedString('\'', KarateTokenTypes.SINGLE_QUOTED_STRING)
            currentChar() in "{}<>" -> readSingleChar(KarateTokenTypes.TEXT)
            isInTableRow && !currentChar().isWhitespace() && currentChar() != '\n' -> readTableCell()
            currentChar().isLetter() -> readKeywordOrText()
            else -> readText()
        }
        
        if (isInTableRow && (currentChar() == '\n' || position >= endOffset)) {
            isInTableRow = false
        }
        
        if (position == startPosition) {
            position++
            tokenType = KarateTokenTypes.TEXT
        }
        
        tokenEnd = position
    }

    private fun currentChar(): Char = if (position < endOffset) buffer!![position] else '\u0000'
    private fun isAtEnd(): Boolean = position >= endOffset

    private fun readWhitespace() {
        while (position < endOffset && currentChar().isWhitespace()) position++
        tokenType = TokenType.WHITE_SPACE
    }

    private fun readComment() {
        position++
        while (position < endOffset && currentChar() != '\n') position++
        tokenType = KarateTokenTypes.COMMENT
    }

    private fun readTag() {
        position++
        while (position < endOffset && !currentChar().isWhitespace() && currentChar() != '\n') position++
        tokenType = KarateTokenTypes.TAG
    }

    private fun readPipe() {
        position++
        if (!isInTableRow) {
            isInTableRow = true
            isFirstTableRow = !hasSeenTableBefore()
        }
        tokenType = KarateTokenTypes.PIPE
    }

    private fun readSingleChar(type: IElementType) {
        position++
        tokenType = type
    }

    private fun readQuotedString(quote: Char, type: IElementType) {
        position++
        while (position < endOffset && currentChar() != quote) {
            if (currentChar() == '\\' && position + 1 < endOffset) position += 2
            else position++
        }
        if (position < endOffset) position++
        tokenType = type
    }

    private fun isDocStringStart(): Boolean =
        position + 2 < endOffset && 
        buffer!!.subSequence(position, position + 3).toString() == "\"\"\""

    private fun readDocString() {
        position += 3
        tokenType = KarateTokenTypes.PYSTRING_QUOTES
    }

    private fun readKeywordOrText() {
        val start = position
        
        if (peekMultiWordKeyword()) {
            readMultiWordKeyword()
            return
        }
        
        while (position < endOffset && (currentChar().isLetterOrDigit() || currentChar() in "_-")) {
            position++
        }
        
        val text = buffer!!.subSequence(start, position).toString().lowercase()
        val keywordEnd = position
        
        tokenType = when (text) {
            "feature" -> KarateTokenTypes.FEATURE_KEYWORD
            "background" -> KarateTokenTypes.BACKGROUND_KEYWORD
            "scenario" -> KarateTokenTypes.SCENARIO_KEYWORD
            "examples" -> KarateTokenTypes.EXAMPLES_KEYWORD
            "rule" -> KarateTokenTypes.RULE_KEYWORD
            "example" -> KarateTokenTypes.EXAMPLE_KEYWORD
            in STEP_KEYWORDS -> KarateTokenTypes.STEP_KEYWORD
            in KARATE_DSL_KEYWORDS -> KarateTokenTypes.KARATE_DSL_KEYWORD
            in HTTP_KEYWORDS -> KarateTokenTypes.HTTP_KEYWORD
            in ACTION_KEYWORDS -> KarateTokenTypes.ACTION_KEYWORD
            else -> KarateTokenTypes.TEXT
        }
        
        // Handle colon for Gherkin keywords
        if (text in GHERKIN_KEYWORDS && position < endOffset && currentChar() == ':') {
            tokenEnd = keywordEnd
        }
    }

    private fun readText() {
        val start = position
        
        while (position < endOffset && !isSpecialChar(currentChar()) && !currentChar().isWhitespace()) {
            position++
        }
        
        val text = buffer!!.subSequence(start, position).toString()
        tokenType = if (text.startsWith("#") && text.length > 1) {
            KarateTokenTypes.VARIABLE
        } else {
            KarateTokenTypes.TEXT
        }
    }

    private fun isSpecialChar(c: Char): Boolean = c in "@#|*\"':<>{}"

    private fun peekMultiWordKeyword(): Boolean {
        val remaining = buffer!!.subSequence(position, endOffset).toString().lowercase()
        return remaining.startsWith("scenario outline")
    }

    private fun readMultiWordKeyword() {
        position += "scenario outline".length
        tokenType = KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD
        
        if (position < endOffset && currentChar() == ':') {
            tokenEnd = position
        }
    }

    private fun hasSeenTableBefore(): Boolean {
        for (i in (position - 1) downTo startOffset) {
            when (buffer!![i]) {
                '|' -> return true
                '\n' -> return false
            }
        }
        return false
    }

    private fun readTableCell() {
        val start = position
        
        while (position < endOffset && currentChar() != '|' && currentChar() != '\n') {
            position++
        }
        
        var end = position
        while (end > start && buffer!![end - 1].isWhitespace()) end--
        
        val cellContent = buffer!!.subSequence(start, end).toString().trim()
        
        tokenType = if (isFirstTableRow) {
            tableHeaderNames.add(cellContent)
            KarateTokenTypes.TABLE_HEADER_CELL
        } else {
            KarateTokenTypes.TABLE_CELL
        }
    }
}