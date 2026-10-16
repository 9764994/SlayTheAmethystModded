# Spire Biomes fog import patch

- Module: `mod.spirebiomes.fog_fbm`, version 1, enabled by default and user configurable.
- Target mod ID: `anniv6` (Spire Biomes).
- Resource: `anniv6Resources/shaders/fog/fragment.fs` only.
- Implementation: `SpireBiomesImportPatchModule` and `SpireBiomesImportCompatPatcher`.

## Fix and scope

The fog history loop repeats the same two FBM calls for every cursor history entry.
The patch hoists their position, motion and base-fog calculation before that loop.
Each entry still computes its own cursor distance, age and fog factor, and applies
the original minimum and early-exit rule. Noise iterations, uniforms, precision,
color, alpha, history limit and framebuffer resolution are unchanged. No Java
classes or other shaders in the imported mod are modified.

This addresses very slow fog rendering on the tested Adreno 730. Earlier experiments
at 2400×1080 with 146 history entries measured roughly 7–8 draw calls/second with
the original and 63–68 with this hoist on MobileGlues and GL4ES. These are game
drawing rates, not physical presentation FPS or a guarantee for other devices.
Frozen-input pixel comparisons on those backends were identical or within 1/255.
This is not a proof of equivalence for all inputs, nor a fix for every slow frame.

## Safety and lifecycle

The accepted source is the shader from Spire Biomes 1.6.4. Its SHA-256 after
normalizing line endings and trimming each line is
`4b4767e4eae3cb7d1ad1b0ff13681390e5673e66dc6651e9c69dc46dd8e578ba`.
Only line-ending and indentation differences are accepted; unknown/modified sources are left
unchanged and reported as unrecognized. Patched sources carry an idempotence
marker. Missing resources are a no-op. Import failures follow the existing
non-blocking shader patch policy.

The standard manual/workshop import registry applies the patch to the working
copy, records the module version in `appliedPatches`, and exposes the enable switch
in the import-patch manager. It does not scan or rewrite the mod on each launch.
Existing installations need to reimport the original mod with the patch enabled.
Disabling the switch only affects future imports; reimport an original copy with
the patch disabled to undo the resource change. Saves are unaffected.

## Regression checks

`SpireBiomesImportCompatPatcherTest` uses the original 1.6.4 shader as a fixture
and checks the isolated hoist, CRLF/indentation compatibility, unknown-source
rejection, exact resource targeting, unrelated JAR entry preservation and
byte-for-byte idempotence. `ImportPatchModuleDefaultsTest` also checks registry
lookup, default enablement and versioned patch metadata.

A local smoke test on the original 1.6.4 JAR verified that only the fog fragment
changed among 2772 archive entries and that its GLSL tokens match the optimized
shader used in the earlier device experiment. The import-patched JAR has not been
deployed to a device as part of this change; full gameplay and additional GPU
coverage remain separate verification work.
