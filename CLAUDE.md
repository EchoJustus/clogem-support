<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
# clogem-support: standing rules for maintainers

The rules every maintainer of this repository follows, Claude and any
other AI agent included (`AGENTS.md` points here). Claude Code loads this
file in every session. The README says how the site is made; this file
holds the policies that every page published here, and every app's page in
its folder of `gh-pages`, must keep.

## Footer copyright notices

(owner, 2026-10-26)

- **One publisher, always in full:** the publisher in a footer's copyright
  notice is `EchoJustus (Jiapeng Ji)`.
  - `EchoJustus` is the legal English name and the GitHub account name.
  - `Jiapeng Ji` is the pinyin of the Singapore Chinese name, which
    carries legal weight in some Singapore contexts.
  - The part in parentheses is never left out, shortened or split from
    the name.
- **A product's own support page** may add the product's name after the
  publisher:

  ```
  © 2026 EchoJustus (Jiapeng Ji). Wmark Pro: Video Watermarker.
  ```

- **The hub's own pages** (the front page in every language, and the 404
  page) name the publisher alone:

  ```
  © 2026 EchoJustus (Jiapeng Ji).
  ```

- **Where it is applied:**
  - The hub's footer takes the name from `copyright-holder` in
    `scripts/portal.clj`, and `test/portal_test.clj` checks the footer on
    every page.
  - Each app's repository writes its own page's footer by this rule.
    Wmark Pro's support page follows it.
- **What it covers:** footers only. The licence lines in source files and
  in the README ("Copyright 2026 EchoJustus. All rights reserved.") and
  the MIT `LICENSE` are a separate matter, and this policy doesn't change
  them.
