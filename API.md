# Collective Unconscious — Backend API Reference

For frontend integration. Backend is Spring Boot, session-based auth (not JWT).

> This document reflects Phase 0–1 (Auth, Tag, Work) and the Phase 2 slice of Contribution
> (draft → submit → read). Ticket/turn-taking, write-permission checks (ownership), concurrency
> safety on submit, and content masking are **not implemented yet** — see "Known gaps" at the
> bottom before building UI that assumes them.

---

## 1. Conventions

### Base URL

All endpoints are relative to the app's origin, e.g. `http://localhost:8080`.

### Authentication — session cookie, not a bearer token

- Login (`POST /api/auth/login`) authenticates and stores the session server-side (Redis-backed).
  The browser receives a `SESSION` cookie automatically; you don't read or store it manually.
- Every subsequent request just needs `credentials: 'include'` (fetch) or `withCredentials: true`
  (axios) so the browser sends the `SESSION` cookie back.
- There is no `Authorization: Bearer ...` header anywhere in this API.

### CSRF — required on every state-changing request

The backend uses cookie-based CSRF protection:

1. The server sets a non-HttpOnly cookie named **`XSRF-TOKEN`** on every response (readable by JS).
2. For every `POST` / `PATCH` / `PUT` / `DELETE` request, read that cookie and echo its value back
   in a request header named **`X-XSRF-TOKEN`**.
3. `GET` requests do not need this header.
4. Missing/wrong token on a state-changing request → `403 Forbidden`.

Axios does this automatically by default if `withCredentials: true` and the cookie/header names
match (they do, `XSRF-TOKEN` / `X-XSRF-TOKEN` are axios's defaults). With `fetch`, you must read
`document.cookie` yourself and set the header manually.

### Auth requirement per route (summary)

- All `GET /api/v1/**` routes are **public** (no login needed) — **except** `GET /api/v1/contributions/drafts`, which requires login.
- Everything else under `/api/v1/**` (POST/PATCH/DELETE) **requires login**.
- `/api/auth/register` and `/api/auth/login` are public. `/api/auth/me` and `/api/auth/logout` require login.
- Not logged in on a route that requires it → `401 Unauthorized`.

### Error response shape

Every non-2xx JSON error body looks like this:

```json
{
  "status": 400,
  "message": "Validation failed",
  "errors": [
    { "field": "title", "message": "Title is required" }
  ]
}
```

`errors` is only present for field-validation failures (400); other errors just have `status` +
`message`. Status code meanings used throughout this API:

| Status | Meaning here |
|---|---|
| 400 | Bean-validation failure, malformed JSON, or a business-rule violation (e.g. content too long) |
| 401 | Not logged in |
| 403 | Logged in but missing/invalid CSRF token (**not** a permissions error — this API has no per-resource permission checks yet, see "Known gaps") |
| 404 | Resource doesn't exist (or, for single-contribution reads, exists but is still a draft) — **also** returned for a request to a path/method this API doesn't define at all (typo'd URL, wrong HTTP verb on an otherwise-real path, etc.) |
| 409 | Conflict with current state (e.g. submitting an already-submitted contribution, editing a submitted contribution as a draft, writing to a completed work, un-completing a completed work) |
| 500 | Unexpected server error — if you get one of these, it's a real bug worth reporting, not an expected outcome |

### Enums

| Enum | Values |
|---|---|
| `Role` | `USER`, `ADMIN` |
| `<br/>` | `NOVEL` (chapters, each contribution may have a `title`), `ARTICLE` (paragraphs, no `title`) |
| `TurnStyle` | `ROBINROUND`, `FREETURN` |
| `WritingMode` | `DEFAULT`, `EXQUISITE_CORPSE` |
| `Visibility` | `PUBLIC` (discoverable/listed), `UNLISTED` (hidden from listing, but still directly readable by id/link) |

---

## 2. Auth — `/api/auth`

### `POST /api/auth/register` — public

Request:
```json
{
  "email": "a@b.com",
  "username": "ash",
  "password": "at-least-8-chars",
  "displayName": "Ash"
}
```
Validation: `email` must be a valid email; `username` 3–30 chars, must not contain `@`;
`password` ≥ 8 chars; `displayName` optional.

→ `201` `UserDto` (see below). `409` if email or username already taken.

### `POST /api/auth/login` — public

Request:
```json
{ "emailOrUsername": "ash", "password": "at-least-8-chars" }
```
→ `200` `UserDto`, and the browser gets a `SESSION` cookie. `401` on bad credentials.

### `GET /api/auth/me` — requires login

→ `200` `UserDto` for the current session. `401` if not logged in.

### `POST /api/auth/logout` — requires login

No body. → `200` and invalidates the session (clears the `SESSION` cookie server-side).

**`UserDto`**
```ts
{ id: string /* UUID */, username: string, email: string, displayName: string | null, role: "USER" | "ADMIN" }
```
Never includes the password.

---

## 3. Tags — `/api/v1/tags`

A flat, global tag pool (not per-work); works reference existing tags or create new ones by name.

### `GET /api/v1/tags` — public

→ `200` `TagDto[]`.

### `POST /api/v1/tags` — requires login

Request:
```json
{ "names": ["fantasy", "sci-fi"] }
```
`names`: 1–10 items, each 1–30 chars, letters/numbers/spaces/`_`/`-` only. Existing names are
reused (idempotent — no duplicate rows), new ones are created.

→ `201` `TagDto[]` (the resolved/created tags, same order not guaranteed).

### `DELETE /api/v1/tags/{id}` — requires login

→ `204` on success. `409` if the tag is still attached to any work.

**`TagDto`**
```ts
{ id: string /* UUID */, name: string, workCount: number }
```

---

## 4. Works — `/api/v1/works`

A "work" is the top-level collaborative-writing project (novel or article).

### `GET /api/v1/works?tagId=` — public

Lists **`PUBLIC`** works only (never `UNLISTED` ones). Optional `tagId` filters to works carrying
that tag.

→ `200` `WorkListItemDto[]`. `404` if `tagId` doesn't match any tag.

**`WorkListItemDto`**
```ts
{
  id: string, title: string, preface: string, tags: TagDto[],
  createdAt: string /* ISO datetime */, updatedAt: string,
  workType: "NOVEL" | "ARTICLE", turnStyle: "ROBINROUND" | "FREETURN",
  writingMode: "DEFAULT" | "EXQUISITE_CORPSE",
  visibility: "PUBLIC" | "UNLISTED",
  requiresAccessCode: boolean, // true if an access code is set — never exposes the code itself
  completed: boolean
}
```

### `POST /api/v1/works` — requires login

Creates a work; the logged-in user becomes `createdAuthor`. Request:
```json
{
  "title": "The Long Story",
  "preface": "Once upon a time...",
  "tagNames": ["fantasy"],
  "workType": "NOVEL",
  "turnStyle": "ROBINROUND",
  "writingMode": "DEFAULT",
  "visibility": "PUBLIC",
  "accessCode": null,
  "maxRounds": null,
  "maxWords": 1000,
  "maxParagraphs": 1,
  "ticketTtlSeconds": 3600,
  "maxParticipants": null
}
```

| Field | Required? | Notes |
|---|---|---|
| `title` | yes | max 255 chars |
| `preface` | yes | |
| `tagNames` | no | reuses/creates tags, same as `POST /tags` |
| `workType`, `turnStyle`, `writingMode` | **yes** | **immutable after creation** — no endpoint changes these later |
| `visibility` | no | defaults to `PUBLIC` |
| `accessCode` | no | null = anyone can join; non-null = an invite code is required to join (join/ticket flow not built yet) |
| `maxRounds` | no | null = unlimited |
| `maxWords` | no | default `1000`; this is a **character count** (see Contribution content rules below), not a word count |
| `maxParagraphs` | no | default `1` |
| `ticketTtlSeconds` | no | default `3600`; unused until the ticket system lands |
| `maxParticipants` | no | null = unlimited |

→ `201` `WorkDetailDto`. `400` if a required/key field is missing or invalid.

### `GET /api/v1/works/{id}` — public

Direct-by-id read — **not** filtered by `visibility` (unlike the list endpoint), so this also
returns `UNLISTED` works if you have the link/id.

→ `200` `WorkDetailDto`. `404` if no such work.

**`WorkDetailDto`**
```ts
{
  id: string, title: string, preface: string, tags: TagDto[],
  workType: "NOVEL" | "ARTICLE", turnStyle: "ROBINROUND" | "FREETURN",
  writingMode: "DEFAULT" | "EXQUISITE_CORPSE", visibility: "PUBLIC" | "UNLISTED",
  requiresAccessCode: boolean, completed: boolean,
  maxRounds: number | null, maxWords: number, maxParagraphs: number,
  ticketTtlSeconds: number, maxParticipants: number | null,
  createdAuthor: AuthorDto, createdAt: string
}
```

**`AuthorDto`** (public-safe author info, used anywhere an author is shown to readers)
```ts
{ id: string, username: string, displayName: string | null } // no email, no role
```

### `PATCH /api/v1/works/{id}` — requires login

**Restricted on purpose**: only `completed` and `accessCode` can change. Any other field in the
body is silently ignored (title/visibility/type/etc. are permanent after creation for now).

Request (both fields optional/independent):
```json
{ "completed": true, "accessCode": "letmein" }
```

| Field | Semantics |
|---|---|
| `completed` | omit/`null` = unchanged; `true` = mark complete; `false` = **only allowed if the work isn't already completed** — completing is one-way, you cannot un-complete a work once it's `true` |
| `accessCode` | omit/`null` = unchanged; non-empty string = set/replace the code; **empty string `""` is rejected with `400`** — once a work has an access code, this endpoint cannot clear it |

→ `200` `WorkDetailDto` (updated). `404` if no such work. `400` if `accessCode: ""`. `409` if
`completed: false` is sent for a work that's already completed.

⚠️ **No ownership check yet** — any logged-in user can currently PATCH any work. Don't build UI
that assumes only the owner can do this; the backend doesn't enforce it yet (tracked as a known gap).

---

## 5. Contributions — `/api/v1/contributions` and `/api/v1/works/{workId}/contributions`

A "contribution" is one chapter (Novel) or paragraph (Article) inside a work. Lifecycle:
**draft** (private, no order) → **submit** (becomes public, gets an order number, author joins the work).
There is no ticket/turn system yet — anyone logged in can create/submit into any non-completed work.

### Content rules (apply to both creating a draft and submitting)

Limits come from the **parent work**, not fixed constants:

| Rule | Enforced against | On violation |
|---|---|---|
| Character count ≤ `work.maxWords` | counts **human-perceived characters** (grapheme clusters — an emoji or accented letter counts as 1), not raw string length/word count | `400` |
| Paragraph count ≤ `work.maxParagraphs` | paragraphs = runs of text split by blank line(s) | `400` |
| Work must not be `completed` | | `409` |
| `title` required (non-blank) if `work.workType === "NOVEL"` | for `ARTICLE`, any `title` you send is ignored/discarded | `400` (Novel missing title) |

This validation runs **again at submit time** (not just at draft creation), since a work's
`completed` state can change between saving a draft and submitting it.

### `POST /api/v1/works/{workId}/contributions` — requires login

Create a new draft under a work. Request:
```json
{ "title": "Chapter One", "content": "Once upon a time..." }
```
`content` is required (non-blank) regardless of the work's limits. `title` is only meaningful for
`NOVEL` works; send `null`/omit it for `ARTICLE`.

→ `201` `ContributionDraftDto`, with `draft` implied true and no `sequenceNumber` yet.
`404` unknown `workId`. `400`/`409` per the content rules above.

### `PATCH /api/v1/contributions/{id}` — requires login

Edit an existing **draft** (2nd+ "save draft" click). Request (both optional, independent):
```json
{ "title": "New title", "content": "New content" }
```
`null`/omitted field = leave unchanged.

→ `200` `ContributionDraftDto` (updated). `404` unknown id. `409` if the contribution was already
submitted (submitted content can't be edited via this endpoint).

### `POST /api/v1/contributions/{id}/submit` — requires login

Finalizes a draft in one atomic step: assigns the next `sequenceNumber` for that work (1, then
2, 3, ... in submission order), flips `draft` to `false`, and adds the contribution's author to
the work's author list. No request body.

→ `200` `ContributionDraftDto` (now with a `sequenceNumber`). `404` unknown id. `409` if already
submitted, or the work has since been marked `completed`. `400` if content no longer fits the
work's limits (e.g. limits were tightened after the draft was written).

**`ContributionDraftDto`** (used for create/update/submit draft responses)
```ts
{
  id: string, workId: string, workTitle: string,
  title: string | null, content: string,
  sequenceNumber: number | null, // null until submitted
  createdAt: string
}
```

### `GET /api/v1/contributions/drafts` — **requires login**

Lists the current user's own drafts (`draft: true` only, across all works). No path/query params.

→ `200` `ContributionDraftDto[]`.

### `GET /api/v1/works/{workId}/contributions?afterSequence=&limit=` — public

Lists **submitted** (non-draft) contributions for a work, in order, **with full body text** for
both Novel and Article works — this is the main "read the work" endpoint. Cursor-paginated for
infinite scroll:

| Query param | Default | Notes |
|---|---|---|
| `afterSequence` | none (starts from the beginning) | pass the last item's `sequenceNumber` from the previous page to get the next batch |
| `limit` | `20` | max items to return |

Frontend infinite-scroll pattern: first call with no `afterSequence`; on each subsequent call,
pass the `sequenceNumber` of the last item you received. Fewer results than `limit` means you've
reached the end.

→ `200` `ContributionDto[]`, ascending by `sequenceNumber`. `404` unknown `workId`.

### `GET /api/v1/contributions/{id}` — public

Single-chapter/paragraph direct read (e.g. deep-linking to one chapter). Only returns **submitted**
contributions — a draft's id returns `404` even to the draft's own author (drafts aren't readable
through this endpoint at all; use `GET /contributions/drafts` for your own drafts).

→ `200` `ContributionDto`. `404` unknown id or still a draft.

**`ContributionDto`** (read view — used by both the cursor list and the single-item read)
```ts
{
  id: string, workId: string, sequenceNumber: number,
  title: string | null, content: string,
  author: AuthorDto, createdAt: string
}
```

---

## 6. Known gaps (don't build UI that assumes these are enforced)

- **No ownership/permission checks anywhere yet.** Any logged-in user can update any work, edit
  or submit any draft. There's no "only the owner" or "only the author" restriction on the backend.
- **No ticket/turn-taking system.** "Whose turn is it to write" isn't implemented — anyone can
  submit at any time. `accessCode` on a work is stored but nothing currently checks it when
  creating/submitting a contribution.
- **No delete endpoint** for works or contributions.
- **`sequenceNumber` assignment isn't concurrency-safe.** Two near-simultaneous submits to the
  same work could theoretically race; low risk at current traffic but don't rely on it under load.
- **`EXQUISITE_CORPSE` writing mode isn't masked yet** — `GET` endpoints always return full content
  regardless of `writingMode`, even though the design intends restricted visibility for that mode.
