"use strict";
// Reviewed first-party source of @lumynoid/json-tools (Story 2.7).
// Lossless JSON whitespace formatter: validates strict JSON syntax, then
// re-emits only whitespace (2-space indent, newline-separated members and
// array items, one trailing newline). String and number lexemes are copied
// verbatim, so numeric precision and duplicate object members survive.
// No dependencies, no lifecycle scripts, no network, no eval.

const WHITESPACE = new Set([" ", "\t", "\r", "\n"]);
const STRUCTURAL = new Set(["{", "}", "[", "]", ",", ":"]);
const ESCAPES = new Set(['"', "\\", "/", "b", "f", "n", "r", "t"]);
const HEX = /[0-9a-fA-F]/;
const NUMBER = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;
const LITERALS = ["null", "true", "false"];

function fail(message, offset) {
  throw new SyntaxError(message + " at token " + offset);
}

function boundaryOk(source, end) {
  if (end >= source.length) return true;
  const next = source[end];
  return WHITESPACE.has(next) || STRUCTURAL.has(next);
}

function tokenize(source) {
  const tokens = [];
  const n = source.length;
  let i = 0;
  while (i < n) {
    const ch = source[i];
    if (WHITESPACE.has(ch)) {
      i++;
      continue;
    }
    if (STRUCTURAL.has(ch)) {
      tokens.push({ t: ch, s: ch });
      i++;
      continue;
    }
    if (ch === '"') {
      const start = i;
      i++;
      for (;;) {
        if (i >= n) fail("unterminated string", start);
        const c = source[i];
        if (c === '"') {
          i++;
          break;
        }
        if (c === "\\") {
          i++;
          if (i >= n) fail("unterminated escape", start);
          const e = source[i];
          if (ESCAPES.has(e)) {
            i++;
          } else if (e === "u") {
            i++;
            for (let k = 0; k < 4; k++) {
              if (i >= n || !HEX.test(source[i])) fail("invalid unicode escape", start);
              i++;
            }
          } else {
            fail("invalid escape", start);
          }
        } else if (c.charCodeAt(0) < 0x20) {
          fail("control character in string", i);
        } else {
          i++;
        }
      }
      tokens.push({ t: "string", s: source.slice(start, i) });
      continue;
    }
    NUMBER.lastIndex = i;
    const match = NUMBER.exec(source);
    if (match !== null) {
      const end = i + match[0].length;
      if (!boundaryOk(source, end)) fail("invalid number", i);
      tokens.push({ t: "number", s: match[0] });
      i = end;
      continue;
    }
    const literal = LITERALS.find((word) => source.startsWith(word, i));
    if (literal !== undefined) {
      const end = i + literal.length;
      if (!boundaryOk(source, end)) fail("invalid literal", i);
      tokens.push({ t: "literal", s: literal });
      i = end;
      continue;
    }
    fail("unexpected character " + JSON.stringify(ch), i);
  }
  return tokens;
}

// Iterative validation and re-emission: no recursion limit, and the output
// budget bounds whitespace expansion for deeply nested documents.
function formatJson(source) {
  const tokens = tokenize(source);
  if (tokens.length === 0) fail("empty input", 0);
  const budget = 256 * 1024; // The host also checks the UTF-8 byte size before publishing.
  const chunks = [];
  let used = 0;
  const emit = (text) => {
    used += text.length;
    if (used > budget) fail("output expansion exceeds budget", 0);
    chunks.push(text);
  };
  const indent = (depth) => "  ".repeat(depth);
  const stack = [];
  let expect = "value";
  let i = 0;
  while (i < tokens.length) {
    const tok = tokens[i];
    switch (expect) {
      case "value":
        if (tok.t === "{") {
          emit("{");
          stack.push("}");
          expect = "key-or-end";
        } else if (tok.t === "[") {
          emit("[");
          stack.push("]");
          expect = "item-or-end";
        } else if (tok.t === "string" || tok.t === "number" || tok.t === "literal") {
          emit(tok.s);
          expect = "after-value";
        } else {
          fail("expected value", i);
        }
        i++;
        break;
      case "key-or-end":
        if (tok.t === "}") {
          emit("}");
          stack.pop();
          expect = "after-value";
          i++;
        } else if (tok.t === "string") {
          emit("\n" + indent(stack.length));
          emit(tok.s);
          expect = "colon";
          i++;
        } else {
          fail("expected object key or }", i);
        }
        break;
      case "item-or-end":
        if (tok.t === "]") {
          emit("]");
          stack.pop();
          expect = "after-value";
          i++;
        } else {
          emit("\n" + indent(stack.length));
          expect = "value";
        }
        break;
      case "item":
        emit("\n" + indent(stack.length));
        expect = "value";
        break;
      case "key":
        if (tok.t !== "string") fail("expected object key", i);
        emit("\n" + indent(stack.length));
        emit(tok.s);
        expect = "colon";
        i++;
        break;
      case "colon":
        if (tok.t !== ":") fail("expected :", i);
        emit(": ");
        expect = "value";
        i++;
        break;
      case "after-value": {
        if (stack.length === 0) fail("unexpected trailing token", i);
        const top = stack[stack.length - 1];
        if (tok.t === ",") {
          emit(",");
          expect = top === "}" ? "key" : "item";
          i++;
        } else if (tok.t === top) {
          emit("\n" + indent(stack.length - 1) + tok.s);
          stack.pop();
          expect = "after-value";
          i++;
        } else {
          fail("expected , or closing bracket", i);
        }
        break;
      }
    }
  }
  if (expect !== "after-value" || stack.length !== 0) fail("unexpected end of input", tokens.length);
  emit("\n");
  return chunks.join("");
}

function main(argv) {
  if (argv.length !== 2) {
    process.stderr.write("usage: format_json <input.json> <output.json>\n");
    return 2;
  }
  const fs = require("node:fs");
  let source;
  try {
    source = fs.readFileSync(argv[0], "utf8");
  } catch (e) {
    process.stderr.write("format_json: cannot read input: " + e.code + "\n");
    return 1;
  }
  let formatted;
  try {
    formatted = formatJson(source);
  } catch (e) {
    process.stderr.write("format_json: " + e.message + "\n");
    return 1;
  }
  try {
    fs.writeFileSync(argv[1], formatted, "utf8");
  } catch (e) {
    process.stderr.write("format_json: cannot write output: " + e.code + "\n");
    return 1;
  }
  return 0;
}

if (require.main === module) process.exitCode = main(process.argv.slice(2));
module.exports = { formatJson };
