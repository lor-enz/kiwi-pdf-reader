# Gitmoji Commit Guide for AI Agents

This repository uses [gitmoji](https://gitmoji.dev) for commit messages. Every commit starts with exactly **one** emoji that states the *intent* of the change. Follow this guide every time you create a commit.

## TL;DR

1. Read the staged diff (`git diff --staged`) and recent history (`git log --oneline -20`).
2. Pick the **one** gitmoji that best describes the primary intent of the change.
3. Write the subject as `<emoji> [(scope):] <imperative summary>`.
4. If the change needs two unrelated emojis to describe it, split it into two commits.

```
✨ (auth): add OAuth login with Google
```

---

## Message format

```
<intention> [(scope):] <subject>

[body]

[footer]
```

- **intention**: one gitmoji, written as the unicode emoji (default) or its `:shortcode:`. Use whichever form the repo history already uses, never both in one message.
- **scope** (optional): the area of the codebase, in parentheses, followed by a colon, e.g. `(api):`, `(parser):`, `(ui/cart):`.
- **subject**: imperative mood ("add", "fix", "remove", not "added" or "adds"), no trailing period, aim for 50 characters, hard limit 72 including the emoji.
- **body** (optional): explain *what* changed and *why*, not *how*. Wrap at 72 characters. Required for 💥 and 🚑️.
- **footer** (optional): issue references (`Closes #123`, `Refs #456`) and breaking-change notes (`BREAKING CHANGE: ...`).

## Hard rules

- Exactly one gitmoji per commit, at the very start of the subject line, followed by a single space.
- Only use gitmojis from the reference tables below. Never invent emojis or shortcodes.
- Copy emojis exactly from the tables. Several (⚡️ 🚑️ 🔒️ ♻️ 🗃️ …) contain an invisible variation selector (U+FE0F) that is easy to drop when retyping.
- One logical change per commit. If you would need ✨ *and* 🐛 to describe it, make two commits.
- Describe the **primary intent**, not every file touched. A feature that ships with tests and docs is ✨. A bug fix with a regression test is 🐛.
- Do not add a Conventional Commits type (`feat:`, `fix:`) unless the repo history already combines them with gitmoji.
- Never use 🍻. Use 💩 only if the user explicitly asks for it.
- Use 🎉 only for the very first commit of a project.
- Use 🚧 only for explicit work-in-progress checkpoints on feature branches, never for commits intended to be merged as-is.

## Match the repository's style

Before your first commit in a session, run `git log --oneline -20` and mirror what you see:

- Unicode emoji (`✨`) vs. shortcode (`:sparkles:`)
- Whether scopes are used, and which scope names exist
- Subject casing (`✨ Add …` vs. `✨ add …`)
- Language of commit messages
- Hybrid formats such as `✨ feat(auth): …`

If the history is empty or inconsistent, use the defaults in this guide: unicode emoji, lowercase imperative subject, optional scope.

---

## Choosing the right gitmoji

Go top to bottom and stop at the first match:

1. **Breaks backward compatibility** (removed or renamed public API, changed behavior callers rely on, incompatible config or schema)? → 💥, and describe the break in the body and a `BREAKING CHANGE:` footer.
2. **Urgent fix for a production incident**? → 🚑️
3. **Fixes a security or privacy vulnerability**? → 🔒️
4. **Adds new capability** for users or API consumers? → ✨
5. **Fixes incorrect behavior**? → 🐛, or 🩹 if it is a trivial, non-critical fix.
6. **Otherwise**, find the most specific match in the reference tables. Prefer specific over generic: ⬆️ over 🔧 for a dependency bump, ⚰️ over 🔥 for unused code, 💬 over 📝 for UI strings.

### Commonly confused choices

**Fixes**
- Normal bug fix → 🐛
- Production is broken and this must ship now → 🚑️
- Small, low-risk fix for a non-critical issue → 🩹

**Removing things**
- Removing code or files in general → 🔥
- Removing code that is unused or unreachable → ⚰️
- Marking code as deprecated but keeping it → 🗑️

**Restructuring**
- Formatting, reordering, whitespace, structure with no behavior change → 🎨
- Refactoring internals (extract, rename, simplify) → ♻️
- Changing architecture (module boundaries, layers, patterns) → 🏗️
- Moving or renaming files, paths, or routes → 🚚

**Tests**
- Adding a test that is expected to fail (TDD "red" step, bug reproduction) → 🧪
- Adding or updating tests, or making tests pass → ✅
- Mocks, fakes, stubs → 🤡
- Snapshot files → 📸

**CI, config, tooling, infrastructure**
- CI is red and you are fixing it → 💚
- Changing CI workflows or build system → 👷
- Config files (linters, tsconfig, app config) → 🔧
- Development scripts (Makefile, `scripts/`, package scripts) → 🔨
- Infrastructure (IaC, containers, orchestration) → 🧱
- `.gitignore` → 🙈
- Developer experience (devcontainers, editor setup, local tooling) → 🧑‍💻

**Dependencies**
- Add → ➕ · Remove → ➖ · Upgrade → ⬆️ · Downgrade → ⬇️ · Pin to exact versions → 📌
- Compiled files or built packages → 📦️
- Adapting code because a third-party API changed → 👽️

**Security**
- Fixing a vulnerability or privacy issue → 🔒️
- Adding, rotating, or updating secrets → 🔐
- Roles, permissions, authorization logic → 🛂

**Words**
- Project documentation (README, `docs/`, guides) → 📝
- Comments in source code, including docstrings → 💡
- User-facing text and literals (labels, error messages) → 💬
- Typos anywhere → ✏️
- Translations and locale files → 🌐

**UI and UX**
- Visual styling (CSS, themes, spacing) → 💄
- Usability and user flows → 🚸
- Accessibility → ♿️
- Responsive layout → 📱
- Animations and transitions → 💫
- Images, fonts, static assets → 🍱

**Robustness**
- Error handling (try/catch, retries, fallbacks) → 🥅
- Input validation (schemas, guards) → 🦺
- Backwards-compatibility shims → 🦖

**Features vs. logic**
- Brand-new capability → ✨
- Changing domain rules in existing features (pricing, eligibility) → 👔
- Making something faster or lighter → ⚡️

---

## Semantic versioning impact

Release tooling can derive the next version from gitmojis, so your choice has consequences:

- **major**: 💥
- **minor**: ✨
- **patch**: every gitmoji marked `patch` in the tables
- **none** (`—`): no release on its own

Never label a breaking change as ✨, and never label an internal refactor as ✨ to make it look bigger.

---

## Reference

### Features and fixes

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| ✨ | `:sparkles:` | Introduce new features. | minor |
| 🐛 | `:bug:` | Fix a bug. | patch |
| 🚑️ | `:ambulance:` | Critical hotfix. | patch |
| 🩹 | `:adhesive_bandage:` | Simple fix for a non-critical issue. | patch |
| 💥 | `:boom:` | Introduce breaking changes. | major |
| 👔 | `:necktie:` | Add or update business logic. | patch |
| 🥅 | `:goal_net:` | Catch errors. | patch |
| 🦺 | `:safety_vest:` | Add or update code related to validation. | — |
| 🦖 | `:t-rex:` | Code that adds backwards compatibility. | — |

### Security

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 🔒️ | `:lock:` | Fix security or privacy issues. | patch |
| 🔐 | `:closed_lock_with_key:` | Add or update secrets. | — |
| 🛂 | `:passport_control:` | Work on code related to authorization, roles and permissions. | patch |

### Code structure and quality

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 🎨 | `:art:` | Improve structure / format of the code. | — |
| ♻️ | `:recycle:` | Refactor code. | — |
| 🏗️ | `:building_construction:` | Make architectural changes. | — |
| ⚡️ | `:zap:` | Improve performance. | patch |
| 🔥 | `:fire:` | Remove code or files. | — |
| ⚰️ | `:coffin:` | Remove dead code. | — |
| 🗑️ | `:wastebasket:` | Deprecate code that needs to be cleaned up. | patch |
| 🚨 | `:rotating_light:` | Fix compiler / linter warnings. | — |
| 🏷️ | `:label:` | Add or update types. | patch |
| 🧵 | `:thread:` | Add or update code related to multithreading or concurrency. | — |
| 🚚 | `:truck:` | Move or rename resources (e.g.: files, paths, routes). | — |

### Tests

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| ✅ | `:white_check_mark:` | Add, update, or pass tests. | — |
| 🧪 | `:test_tube:` | Add a failing test. | — |
| 🤡 | `:clown_face:` | Mock things. | — |
| 📸 | `:camera_flash:` | Add or update snapshots. | — |

### Documentation and text

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 📝 | `:memo:` | Add or update documentation. | — |
| 💡 | `:bulb:` | Add or update comments in source code. | — |
| 💬 | `:speech_balloon:` | Add or update text and literals. | patch |
| ✏️ | `:pencil2:` | Fix typos. | patch |
| 📄 | `:page_facing_up:` | Add or update license. | — |
| 👥 | `:busts_in_silhouette:` | Add or update contributor(s). | — |

### UI, UX and frontend

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 💄 | `:lipstick:` | Add or update the UI and style files. | patch |
| 🚸 | `:children_crossing:` | Improve user experience / usability. | patch |
| ♿️ | `:wheelchair:` | Improve accessibility. | patch |
| 📱 | `:iphone:` | Work on responsive design. | patch |
| 💫 | `:dizzy:` | Add or update animations and transitions. | patch |
| 🍱 | `:bento:` | Add or update assets. | patch |
| 🌐 | `:globe_with_meridians:` | Internationalization and localization. | patch |
| 🔍️ | `:mag:` | Improve SEO. | patch |
| 🥚 | `:egg:` | Add or update an easter egg. | patch |
| ✈️ | `:airplane:` | Improve offline support. | — |

### Dependencies and packages

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| ➕ | `:heavy_plus_sign:` | Add a dependency. | patch |
| ➖ | `:heavy_minus_sign:` | Remove a dependency. | patch |
| ⬆️ | `:arrow_up:` | Upgrade dependencies. | patch |
| ⬇️ | `:arrow_down:` | Downgrade dependencies. | patch |
| 📌 | `:pushpin:` | Pin dependencies to specific versions. | patch |
| 📦️ | `:package:` | Add or update compiled files or packages. | patch |
| 👽️ | `:alien:` | Update code due to external API changes. | patch |

### Build, CI, config and infrastructure

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 👷 | `:construction_worker:` | Add or update CI build system. | — |
| 💚 | `:green_heart:` | Fix CI Build. | — |
| 🔧 | `:wrench:` | Add or update configuration files. | patch |
| 🔨 | `:hammer:` | Add or update development scripts. | — |
| 🧱 | `:bricks:` | Infrastructure related changes. | — |
| 🩺 | `:stethoscope:` | Add or update healthcheck. | — |
| 🙈 | `:see_no_evil:` | Add or update a .gitignore file. | — |
| 🧑‍💻 | `:technologist:` | Improve developer experience. | — |

### Data, observability and product

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 🗃️ | `:card_file_box:` | Perform database related changes. | patch |
| 🌱 | `:seedling:` | Add or update seed files. | — |
| 📈 | `:chart_with_upwards_trend:` | Add or update analytics or track code. | patch |
| 🔊 | `:loud_sound:` | Add or update logs. | — |
| 🔇 | `:mute:` | Remove logs. | — |
| 🧐 | `:monocle_face:` | Data exploration/inspection. | — |
| 🚩 | `:triangular_flag_on_post:` | Add, update, or remove feature flags. | patch |
| ⚗️ | `:alembic:` | Perform experiments. | patch |
| 💸 | `:money_with_wings:` | Add sponsorships or money related infrastructure. | — |

### Git workflow and releases

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 🎉 | `:tada:` | Begin a project. | — |
| 🔖 | `:bookmark:` | Release / Version tags. | — |
| 🚀 | `:rocket:` | Deploy stuff. | — |
| 🔀 | `:twisted_rightwards_arrows:` | Merge branches. | — |
| ⏪️ | `:rewind:` | Revert changes. | patch |
| 🚧 | `:construction:` | Work in progress. | — |

### Do not use (unless the user explicitly asks)

| Emoji | Shortcode | Meaning | SemVer |
|---|---|---|---|
| 💩 | `:poop:` | Write bad code that needs to be improved. | — |
| 🍻 | `:beers:` | Write code drunkenly. | — |

---

## Examples

### Good

```
✨ (cart): add discount code field
🐛 (parser): handle empty input without crashing
🚑️ restore checkout after payment provider timeout
🩹 (ui): correct default page size in settings
⚡️ (search): cache tokenized queries
♻️ (auth): extract token refresh into its own service
⬆️ upgrade react from 18.2 to 19.0
➕ add zod for request validation
🦺 (api): validate signup payload with zod
🗃️ add index on orders.created_at
🧪 (dates): add failing test for leap-year rollover
🔖 release v2.4.0
⏪️ revert "✨ (cart): add discount code field"
```

### Bad, and why

| Message | Problem | Fix |
|---|---|---|
| `✨🐛 add export and fix import` | Two intents in one commit | Split into `✨ add CSV export` and `🐛 fix import of quoted fields` |
| `🐛 Fixed the bug.` | Vague, past tense, trailing period | `🐛 (login): reject expired session tokens` |
| `update stuff` | No gitmoji, no information | Pick a gitmoji and describe the change |
| `🎨 add dark mode` | 🎨 is formatting only; this is a feature | `✨ (ui): add dark mode` |
| `✨ bump lodash to 4.17.21` | Dependency upgrade is not a feature | `⬆️ upgrade lodash to 4.17.21` |
| `:bug: 🐛 fix login` | Both forms of the same gitmoji | Use one: `🐛 fix login` |
| `🔥 remove v1 API` | Removing public API breaks consumers | `💥 (api): remove v1 endpoints` with a body and footer |

---

## Committing from the shell

Single-line commit:

```bash
git commit -m "✨ (auth): add OAuth login with Google"
```

Subject plus body with multiple `-m` flags (each becomes its own paragraph):

```bash
git commit -m "🐛 (parser): handle empty input without crashing" \
           -m "Empty files returned undefined and crashed the renderer. Return an empty AST instead."
```

Longer messages: use a quoted heredoc so backticks and `$` are not expanded by the shell:

```bash
git commit -F - <<'EOF'
💥 (api): remove v1 endpoints

The v1 REST endpoints were deprecated in 3.2 and are now removed.
Clients must migrate to the /v2 routes.

BREAKING CHANGE: all /v1/* routes now return 404.
Closes #481
EOF
```

Reverts: `git revert` generates a message starting with `Revert "…"`. Rewrite the subject to `⏪️ revert "<original subject>"` before committing (use `git revert --no-commit` and then `git commit`).

## Final checklist

Before running `git commit`, confirm:

- [ ] Exactly one gitmoji, from this file, at the start of the line
- [ ] The gitmoji matches the primary intent (use the decision order above)
- [ ] Breaking changes use 💥 and explain the break in the body
- [ ] Subject is imperative, specific, no trailing period, 72 characters or fewer
- [ ] Style (emoji vs. shortcode, scope, casing) matches the repo history
- [ ] The commit contains one logical change