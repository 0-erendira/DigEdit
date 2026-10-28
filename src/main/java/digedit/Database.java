package digedit;

import digedit.Model.Discussion;
import digedit.Model.Link;
import digedit.Model.LinkType;
import digedit.Model.Post;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * All SQLite access lives here. The database is a single file (digedit.db)
 * created next to the program on first run and filled with sample data.
 */
public class Database {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final Connection conn;

    public Database(String file) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            createSchema();
            if (listDiscussions().isEmpty()) seed();
        } catch (SQLException e) {
            throw new RuntimeException("Could not open database: " + e.getMessage(), e);
        }
    }

    public static String now() { return LocalDateTime.now().format(FMT); }

    // ---------------------------------------------------------------- schema

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS discussions (
                    id    INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    discussion_id INTEGER NOT NULL REFERENCES discussions(id) ON DELETE CASCADE,
                    author        TEXT NOT NULL,
                    title         TEXT NOT NULL,
                    body          TEXT NOT NULL DEFAULT '',
                    created_at    TEXT NOT NULL,
                    x             INTEGER NOT NULL,
                    y             INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS links (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    discussion_id INTEGER NOT NULL REFERENCES discussions(id) ON DELETE CASCADE,
                    from_post     INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    to_post       INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                    type          TEXT NOT NULL,
                    UNIQUE (from_post, to_post)
                )""");
        }
    }

    // ----------------------------------------------------------- discussions

    public List<Discussion> listDiscussions() {
        List<Discussion> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, title FROM discussions ORDER BY id")) {
            while (rs.next()) out.add(new Discussion(rs.getInt(1), rs.getString(2)));
        } catch (SQLException e) { fail(e); }
        return out;
    }

    public Discussion addDiscussion(String title) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO discussions(title) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            ps.executeUpdate();
            return new Discussion(generatedId(ps), title);
        } catch (SQLException e) { fail(e); return null; }
    }

    public void renameDiscussion(int id, String title) {
        update("UPDATE discussions SET title = ? WHERE id = ?", title, id);
    }

    public void deleteDiscussion(int id) {
        update("DELETE FROM discussions WHERE id = ?", id);
    }

    // ----------------------------------------------------------------- posts

    public List<Post> listPosts(int discussionId) {
        List<Post> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, discussion_id, author, title, body, created_at, x, y "
              + "FROM posts WHERE discussion_id = ? ORDER BY id")) {
            ps.setInt(1, discussionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Post p = new Post();
                    p.id = rs.getInt(1); p.discussionId = rs.getInt(2);
                    p.author = rs.getString(3); p.title = rs.getString(4);
                    p.body = rs.getString(5); p.createdAt = rs.getString(6);
                    p.x = rs.getInt(7); p.y = rs.getInt(8);
                    out.add(p);
                }
            }
        } catch (SQLException e) { fail(e); }
        return out;
    }

    /** Inserts a post. If p.id > 0 that id is reused (used by Undo to restore a deleted post). */
    public void insertPost(Post p) {
        String sql = p.id > 0
                ? "INSERT INTO posts(discussion_id, author, title, body, created_at, x, y, id) VALUES (?,?,?,?,?,?,?,?)"
                : "INSERT INTO posts(discussion_id, author, title, body, created_at, x, y) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, p.discussionId); ps.setString(2, p.author); ps.setString(3, p.title);
            ps.setString(4, p.body); ps.setString(5, p.createdAt); ps.setInt(6, p.x); ps.setInt(7, p.y);
            if (p.id > 0) ps.setInt(8, p.id);
            ps.executeUpdate();
            if (p.id <= 0) p.id = generatedId(ps);
        } catch (SQLException e) { fail(e); }
    }

    public void updatePost(Post p) {
        update("UPDATE posts SET author = ?, title = ?, body = ? WHERE id = ?",
                p.author, p.title, p.body, p.id);
    }

    public void movePost(int id, int x, int y) {
        update("UPDATE posts SET x = ?, y = ? WHERE id = ?", x, y, id);
    }

    public void deletePost(int id) {
        update("DELETE FROM posts WHERE id = ?", id);
    }

    // ----------------------------------------------------------------- links

    public List<Link> listLinks(int discussionId) {
        List<Link> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, discussion_id, from_post, to_post, type FROM links WHERE discussion_id = ?")) {
            ps.setInt(1, discussionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Link(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            LinkType.valueOf(rs.getString(5))));
                }
            }
        } catch (SQLException e) { fail(e); }
        return out;
    }

    /** Inserts a link and returns it with its id. A link with id > 0 keeps that id (Undo). */
    public Link insertLink(Link l) {
        String sql = l.id() > 0
                ? "INSERT INTO links(discussion_id, from_post, to_post, type, id) VALUES (?,?,?,?,?)"
                : "INSERT INTO links(discussion_id, from_post, to_post, type) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, l.discussionId()); ps.setInt(2, l.fromPost());
            ps.setInt(3, l.toPost()); ps.setString(4, l.type().name());
            if (l.id() > 0) ps.setInt(5, l.id());
            ps.executeUpdate();
            int id = l.id() > 0 ? l.id() : generatedId(ps);
            return new Link(id, l.discussionId(), l.fromPost(), l.toPost(), l.type());
        } catch (SQLException e) { fail(e); return null; }
    }

    public boolean linkExists(int fromPost, int toPost) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM links WHERE (from_post = ? AND to_post = ?) OR (from_post = ? AND to_post = ?)")) {
            ps.setInt(1, fromPost); ps.setInt(2, toPost); ps.setInt(3, toPost); ps.setInt(4, fromPost);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { fail(e); return false; }
    }

    public void deleteLink(int id) {
        update("DELETE FROM links WHERE id = ?", id);
    }

    // --------------------------------------------------------------- helpers

    private void update(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        } catch (SQLException e) { fail(e); }
    }

    private static int generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getInt(1);
        }
    }

    private static void fail(SQLException e) {
        throw new RuntimeException("Database error: " + e.getMessage(), e);
    }

    // ----------------------------------------------------------- sample data

    private void seed() {
        Discussion d1 = addDiscussion("Should CSUN switch to a 4-day class week?");
        int a = post(d1, "Maria G.", "Proposal: 4-day week",
                "Move all M/W/F classes to Mon-Thu with longer sessions. Fridays become open lab and office-hour days.", 380, 40);
        int b = post(d1, "Kevin T.", "Commuters would love this",
                "I drive 50 minutes each way. One less day on the 405 is a huge quality-of-life win.", 120, 190);
        int c = post(d1, "Priya S.", "Longer classes hurt focus",
                "Attention drops off after about an hour. 2-hour lectures would need built-in breaks.", 400, 200);
        int e = post(d1, "Jordan L.", "What about lab sections?",
                "Labs are already 3 hours. Where do they fit if Friday is gone?", 680, 190);
        int f = post(d1, "Kevin T.", "Breaks solve that",
                "A 10-minute break halfway through fixes the focus problem. Plenty of grad classes do this.", 260, 350);
        int g = post(d1, "Alex R.", "Friday labs could stay",
                "Keep labs on Friday, move only lectures. Best of both.", 650, 350);
        int h = post(d1, "Priya S.", "Breaks help, but not enough",
                "Breaks help, but 4 long days in a row is still exhausting for students who also work.", 260, 500);
        link(d1, b, a, LinkType.AGREE);
        link(d1, c, a, LinkType.DISAGREE);
        link(d1, e, a, LinkType.REPLY);
        link(d1, f, c, LinkType.DISAGREE);
        link(d1, g, e, LinkType.REPLY);
        link(d1, h, f, LinkType.DISAGREE);

        Discussion d2 = addDiscussion("Best first programming language");
        int p1 = post(d2, "Sam K.", "Python first, always",
                "Readable syntax, huge ecosystem, and students see results fast.", 360, 40);
        int p2 = post(d2, "Dana W.", "Java teaches fundamentals",
                "Static types and explicit structure make concepts like classes and interfaces stick.", 110, 200);
        int p3 = post(d2, "Chris M.", "Agree on Python",
                "Our intro class switched to Python and pass rates went up.", 400, 200);
        int p4 = post(d2, "Lee H.", "What about JavaScript?",
                "Everyone uses a browser. Instant visual feedback is motivating.", 680, 200);
        int p5 = post(d2, "Sam K.", "Types can come later",
                "Learn to think in steps first, then learn type systems in the second course.", 140, 360);
        link(d2, p2, p1, LinkType.DISAGREE);
        link(d2, p3, p1, LinkType.AGREE);
        link(d2, p4, p1, LinkType.REPLY);
        link(d2, p5, p2, LinkType.DISAGREE);
    }

    private int post(Discussion d, String author, String title, String body, int x, int y) {
        Post p = new Post();
        p.discussionId = d.id(); p.author = author; p.title = title; p.body = body;
        p.createdAt = now(); p.x = x; p.y = y;
        insertPost(p);
        return p.id;
    }

    private void link(Discussion d, int from, int to, LinkType type) {
        insertLink(new Link(0, d.id(), from, to, type));
    }
}
