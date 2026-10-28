package digedit;

/** Plain data classes for the three database tables. */
public final class Model {
    private Model() {}

    public record Discussion(int id, String title) {
        @Override public String toString() { return title; }
    }

    public static final class Post {
        public int id;
        public int discussionId;
        public String author;
        public String title;
        public String body;
        public String createdAt;
        public int x;
        public int y;

        public Post copy() {
            Post p = new Post();
            p.id = id; p.discussionId = discussionId; p.author = author; p.title = title;
            p.body = body; p.createdAt = createdAt; p.x = x; p.y = y;
            return p;
        }

        /** Case-insensitive match against title, author, and body. */
        public boolean matches(String query) {
            String q = query.toLowerCase();
            return title.toLowerCase().contains(q)
                    || author.toLowerCase().contains(q)
                    || body.toLowerCase().contains(q);
        }
    }

    public enum LinkType {
        REPLY("Reply to"), AGREE("Agrees with"), DISAGREE("Disagrees with");

        public final String label;
        LinkType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** A directed link: fromPost replies to / agrees with / disagrees with toPost. */
    public record Link(int id, int discussionId, int fromPost, int toPost, LinkType type) {}
}
