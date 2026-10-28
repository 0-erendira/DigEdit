# DigEdit — COMP 485 Project 4

A visual editor for discussion graphs. Java (Swing) + SQLite.

## Run it (VS Code)
1. Install a JDK (Temurin 21 from adoptium.net) and the **Extension Pack for Java** in VS Code.
2. File → Open Folder → select this `DigEdit` folder (the one containing `pom.xml`).
3. Wait for the Java extension to finish importing (status bar, bottom left).
4. Open `src/main/java/digedit/Main.java` and click **Run** above `public static void main`.

On first run the app creates `digedit.db` in the project folder and fills it with two
sample discussions. Delete that file to reset to the sample data. The database file is
not committed to Git, so each teammate has their own copy.

## Team workflow (Git)
- Before you start working: Source Control panel → **Sync Changes** (downloads teammates' work).
- When you finish a chunk: type a short message, click **Commit**, then **Sync Changes**.
- Tell the group before editing a file someone else is working on.

## Code layout
| File | Purpose |
|---|---|
| `Main.java` | Entry point |
| `Model.java` | Data classes: Discussion, Post, Link, LinkType |
| `Database.java` | SQLite schema, sample data, all queries |
| `GraphCanvas.java` | Draws the graph; dragging, selection, linking, zoom, search highlight |
| `MainFrame.java` | Window, menus, toolbar, details panel, undo, search |
| `PostDialog.java` | Add/edit post form with inline validation |

## Database schema
- `discussions(id, title)`
- `posts(id, discussion_id → discussions, author, title, body, created_at, x, y)`
- `links(id, discussion_id → discussions, from_post → posts, to_post → posts, type)`
  - `type` is REPLY, AGREE, or DISAGREE. Deleting a post or discussion cascades to its links.
