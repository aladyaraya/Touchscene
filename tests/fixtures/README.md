# Original baseline

`original-baseline.json` is generated from the original Touchscene commit
`3eea391f32985ddd79f94a560fc1dabc27aa5b8c`, never from the current working tree.
It records 101 original source paths and SHA-256 values (UTF-8, CRLF normalized
to LF, trailing whitespace at EOF trimmed), five original layout/resource
references, and the protected local speech/description block.

The guard still checks 98 source files exactly; only the three previously
approved cloud adapters are exempt. Layout and interaction exceptions have not
been expanded. This fixture lets checks run from ZIP downloads or shallow clones.

Regenerate only when auditing the original baseline, in a clone containing that
commit: `node tests/fixtures/generate-original-baseline.mjs`. Review the diff.
Updating this file is not a way to approve algorithm changes.
