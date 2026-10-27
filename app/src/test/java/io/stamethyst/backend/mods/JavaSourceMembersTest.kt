package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JavaSourceMembersTest {
    @Test
    fun members_findsFieldsWithBracesInsideStrings() {
        val members = JavaSourceMembers.members(SAMPLE)

        val braces = members.first { it.name == "BRACES" }
        assertEquals("field", braces.kind)
        assertEquals("private static final String BRACES = \"a { b } c\";", JavaSourceMembers.text(SAMPLE, braces))
    }

    @Test
    fun members_keepsAnonymousClassFieldWhole() {
        val members = JavaSourceMembers.members(SAMPLE)

        val listener = members.first { it.name == "listener" }
        val text = JavaSourceMembers.text(SAMPLE, listener)
        assertEquals("field", listener.kind)
        assertTrue("anonymous body must stay inside the field: $text", text.contains("System.out.println"))
        assertTrue("field must end at its semicolon: $text", text.endsWith("};"))
    }

    @Test
    fun members_keepsArrayInitializerFieldWhole() {
        val members = JavaSourceMembers.members(SAMPLE)

        val values = members.first { it.name == "values" }
        assertEquals("private int[] values = {1, 2, 3};", JavaSourceMembers.text(SAMPLE, values))
    }

    @Test
    fun members_separatesOverloadsAndClassifiesKinds() {
        val members = JavaSourceMembers.members(SAMPLE)

        val size = members.filter { it.name == "size" }
        assertEquals(2, size.size)
        assertTrue(size.all { it.kind == "method" })

        val constructor = members.first { it.name == "Big" }
        assertEquals("constructor", constructor.kind)

        val inner = members.first { it.name == "Inner" }
        assertEquals("type", inner.kind)
    }

    @Test
    fun members_stripsAnnotationBeforeClassifying() {
        val members = JavaSourceMembers.members(SAMPLE)

        val annotated = members.first { it.name == "annotated" }
        assertEquals("method", annotated.kind)
        assertEquals("public int annotated()", JavaSourceMembers.declaration(annotated))
    }

    @Test
    fun members_survivesCharAndEscapedQuoteLiterals() {
        val members = JavaSourceMembers.members(SAMPLE)

        val tricky = members.first { it.name == "tricky" }
        val text = JavaSourceMembers.text(SAMPLE, tricky)
        assertTrue(text.contains("char open = '{';"))
        assertTrue(text.contains("String quote = \"\\\"\";"))
        assertTrue("method body must close exactly once: $text", text.endsWith("}"))
    }

    @Test
    fun skeleton_collapsesBodiesAndKeepsSignatures() {
        val skeleton = JavaSourceMembers.skeleton(SAMPLE)
        assertNotNull(skeleton)
        val text = skeleton!!

        assertTrue(text.contains("public class Big {"))
        assertTrue("signature must be present", text.contains("public int size() { /* … */ }"))
        assertTrue("overload must be present", text.contains("public int size(int extra) { /* … */ }"))
        assertTrue("nested type must be present", text.contains("static class Inner {"))
        assertTrue("nested member must be present", text.contains("int value;"))
        assertFalse("bodies must be collapsed", text.contains("return this.values.length;"))
        assertFalse("anonymous bodies must be collapsed", text.contains("System.out.println"))
    }

    @Test
    fun skeleton_balancesBraces() {
        val skeleton = JavaSourceMembers.skeleton(SAMPLE)!!
        var depth = 0
        skeleton.forEach { char ->
            when (char) {
                '{' -> depth++
                '}' -> depth--
            }
            assertTrue("brace depth went negative at $char", depth >= 0)
        }
        assertEquals("skeleton must be balanced", 0, depth)
    }

    @Test
    fun select_prefersExactNameThenCaseInsensitive() {
        val members = JavaSourceMembers.members(SAMPLE)

        val exact = JavaSourceMembers.select(members, "size", 10)
        assertEquals(2, exact.size)
        assertTrue(exact.all { it.name == "size" })

        val dotted = JavaSourceMembers.select(members, "com.example.Big.size", 10)
        assertEquals(2, dotted.size)

        val fuzzy = JavaSourceMembers.select(members, "SIZE", 10)
        assertEquals(2, fuzzy.size)

        assertTrue(JavaSourceMembers.select(members, "missing", 10).isEmpty())
        assertTrue(JavaSourceMembers.select(members, "  ", 10).isEmpty())
    }

    @Test
    fun select_honoursLimit() {
        val members = JavaSourceMembers.members(SAMPLE)

        assertEquals(1, JavaSourceMembers.select(members, "size", 1).size)
    }

    @Test
    fun text_returnsOnlyTheRequestedMember() {
        val members = JavaSourceMembers.members(SAMPLE)

        val size = JavaSourceMembers.select(members, "size", 1).single()
        val text = JavaSourceMembers.text(SAMPLE, size)

        assertTrue("the annotation belongs to the member: $text", text.startsWith("@Deprecated"))
        assertTrue(text.contains("public int size()"))
        assertTrue(text.contains("return this.values.length;"))
        assertFalse("must not leak the next member", text.contains("public int size(int extra)"))
    }

    @Test
    fun members_ignoreAnnotationBracesBeforeTheType() {
        val source = """
            package demo;

            @SuppressWarnings({"unchecked", "rawtypes"})
            public class Annotated {
                public void run() {
                    int x = 1;
                }
            }
        """.trimIndent()

        val members = JavaSourceMembers.members(source)
        assertEquals(listOf("run"), members.map { it.name })

        val skeleton = JavaSourceMembers.skeleton(source)!!
        assertTrue(skeleton.contains("@SuppressWarnings({\"unchecked\", \"rawtypes\"})"))
        assertTrue(skeleton.contains("public void run() { /* … */ }"))
    }

    @Test
    fun members_returnsEmptyForSourceWithoutTypeBody() {
        assertTrue(JavaSourceMembers.members("package a;\n").isEmpty())
        assertEquals(null, JavaSourceMembers.skeleton("package a;\n"))
    }

    @Test
    fun members_parseRealCfrOutput() {
        val jar = listOf(
            File("src/main/assets/components/mods/BaseMod.jar"),
            File("app/src/main/assets/components/mods/BaseMod.jar"),
        ).firstOrNull(File::isFile)
        org.junit.Assume.assumeTrue("BaseMod.jar is not checked out", jar != null)
        val scratch = java.nio.file.Files.createTempDirectory("cfr-members").toFile()

        val result = AgentPatchClassDecompiler.decompileClass(
            jarFile = jar!!,
            binaryName = "basemod.abstracts.CustomCard",
            classpath = jar.absolutePath,
            scratchDir = scratch,
        )
        val members = JavaSourceMembers.members(result.source)

        assertTrue("CFR output must expose members", members.size > 10)
        assertTrue("constructor must be recognised", members.any { it.kind == "constructor" })
        assertTrue("methods must be recognised", members.any { it.kind == "method" })
        assertTrue(
            "fields must be recognised",
            members.any { it.kind == "field" && it.name == "textureImg" },
        )

        val skeleton = JavaSourceMembers.skeleton(result.source)
        assertNotNull(skeleton)
        assertEquals(
            "the skeleton must keep every member",
            members.map { it.name to it.kind },
            JavaSourceMembers.members(skeleton!!).map { it.name to it.kind },
        )

        val named = members.first { it.kind == "method" && it.name.isNotEmpty() }
        assertTrue(JavaSourceMembers.text(result.source, named).contains(named.name))
    }

    private companion object {
        val SAMPLE = """
            package com.example;

            import java.util.List;

            public class Big {
                private static final String BRACES = "a { b } c";
                private final Runnable listener = new Runnable() {
                    public void run() {
                        System.out.println("run { }");
                    }
                };
                private int[] values = {1, 2, 3};

                public Big() {
                    super();
                }

                @Deprecated
                public int size() {
                    return this.values.length;
                }

                public int size(int extra) {
                    return size() + extra;
                }

                public int annotated() {
                    return 1;
                }

                public void tricky() {
                    char open = '{';
                    String quote = "\"";
                    String close = "}";
                }

                static class Inner {
                    int value;

                    Inner() {
                    }
                }
            }
        """.trimIndent()
    }
}
