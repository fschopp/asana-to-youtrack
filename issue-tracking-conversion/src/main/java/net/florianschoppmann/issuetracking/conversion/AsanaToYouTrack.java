package net.florianschoppmann.issuetracking.conversion;

import com.asana.models.Story;
import com.asana.models.Tag;
import com.asana.models.Task;
import com.google.api.client.util.DateTime;
import net.florianschoppmann.issuetracking.asana.AsanaExportWarnings;
import net.florianschoppmann.issuetracking.asana.AsanaReference;
import net.florianschoppmann.issuetracking.asana.Export;
import net.florianschoppmann.issuetracking.asana.WrappedAttachment;
import net.florianschoppmann.issuetracking.asana.WrappedComment;
import net.florianschoppmann.issuetracking.asana.WrappedTask;
import net.florianschoppmann.issuetracking.asana.WrappedUser;
import net.florianschoppmann.issuetracking.util.LazyContext;
import net.florianschoppmann.issuetracking.util.ReferencingIssue;
import net.florianschoppmann.issuetracking.youtrack.Attachments;
import net.florianschoppmann.issuetracking.youtrack.Attachments.Attachment;
import net.florianschoppmann.issuetracking.youtrack.IssueUpdates;
import net.florianschoppmann.issuetracking.youtrack.IssueUpdates.IssueUpdate;
import net.florianschoppmann.issuetracking.youtrack.rest.IssueTag;
import net.florianschoppmann.issuetracking.youtrack.restold.Issues;
import net.florianschoppmann.issuetracking.youtrack.restold.Issues.Issue;
import net.florianschoppmann.issuetracking.youtrack.restold.Issues.Issue.Comment;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class AsanaToYouTrack {
    private static final String UNKNOWN_LOGIN = "##unknown-login##";

    private final Export export;

    public AsanaToYouTrack(Export export) {
        this.export = Objects.requireNonNull(export);
    }

    private static final class Request {
        private final Export.Result result;
        private final ConversionWarnings conversionWarnings;
        private final Path attachmentsBasePath;
        private final LazyContextImpl context;
        private final boolean bracketsAsEstimates;

        private Request(Export.Result result, ConversionWarnings conversionWarnings, Path attachmentsBasePath,
                LazyContextImpl context, Options options) {
            this.result = result;
            this.conversionWarnings = conversionWarnings;
            this.attachmentsBasePath = attachmentsBasePath;
            this.context = context;
            bracketsAsEstimates = options.estimatesInBrackets;
        }

        private static void setField(Issue issue, String fieldName, @Nullable String fieldValue) {
            if (fieldValue == null) {
                return;
            }

            var field = new Issue.Field();
            field.setName(fieldName);
            field.getValue().add(fieldValue);
            issue.getField().add(field);
        }

        private static void setField(Issue issue, String fieldName, long fieldValue) {
            setField(issue, fieldName, Long.toString(fieldValue));
        }

        private static void setField(Issue issue, String fieldName, @Nullable DateTime fieldValue) {
            if (fieldValue == null) {
                return;
            }

            setField(issue, fieldName, fieldValue.getValue());
        }

        private static void setField(Issue issue, String fieldName, Stream<String> stream) {
            var field = new Issue.Field();
            field.setName(fieldName);
            List<String> values = field.getValue();
            stream.forEach(values::add);
            if (!values.isEmpty()) {
                issue.getField().add(field);
            }
        }

        private static void setToStringIfNotNull(Consumer<String> setter, @Nullable String value) {
            if (value != null) {
                setter.accept(value);
            }
        }

        private void convertComment(WrappedComment wrappedComment, Comment comment) {
            Story story = wrappedComment.getStory();
            setToStringIfNotNull(comment::setAuthor, context.youTrackUserFromEmail(story.createdBy.email));
            comment.setCreated(Long.toString(wrappedComment.getCreatedAt().toEpochMilli()));
            comment.setText(story.isEdited
                ? (wrappedComment.getMarkdownText().toStringWithContext(context) + "\n\n***\n\n[edited]")
                : wrappedComment.getMarkdownText().toStringWithContext(context)
            );
            comment.setMarkdown(Boolean.TRUE.toString());
        }

        private String descriptionForTask(WrappedTask wrappedTask) {
            StringBuilder descriptionBuilder
                = new StringBuilder(wrappedTask.getMarkdownDescription().toStringWithContext(context));
            boolean firstLink = true;
            for (WrappedAttachment wrappedAttachment : wrappedTask.getAttachments()) {
                @Nullable Path downloadPath = wrappedAttachment.getDownloadPath();
                var asanaAttachment = wrappedAttachment.getAttachment();
                if (downloadPath == null && asanaAttachment.viewUrl != null) {
                    if (firstLink) {
                        descriptionBuilder.append("\n\n***\n\n## Attached Links\n");
                        firstLink = false;
                    }
                    descriptionBuilder.append("- [").append(asanaAttachment.name).append(" @ ")
                        .append(asanaAttachment.host).append("](").append(asanaAttachment.viewUrl).append(")\n");
                }
            }
            return descriptionBuilder.toString();
        }

        private void convertPredefinedFields(WrappedTask wrappedTask, String taskName, Issue issue,
                @Nullable String updaterLoginName) {
            // These are the fields predefined by https://www.jetbrains.com/help/youtrack/incloud/Import-Issues.html
            Task asanaTask = wrappedTask.getTask();
            setField(issue, "numberInProject", wrappedTask.getNumberInProject());
            setField(issue, "summary", taskName);
            setField(issue, "description", descriptionForTask(wrappedTask));
            setField(issue, "markdown", Boolean.TRUE.toString());
            setField(issue, "created", wrappedTask.getCreatedAt().toEpochMilli());
            setField(issue, "updated", asanaTask.modifiedAt);
            setField(issue, "updaterName", updaterLoginName);
            setField(issue, "resolved", asanaTask.completedAt);
            setField(issue, "reporterName", context.youTrackUserFromEmail(asanaTask.createdBy.email));
            setField(issue, "voterName",
                asanaTask.likes.stream()
                    .map(like -> like.user.email)
                    .map(context::youTrackUserFromEmail)
                    .filter(Objects::nonNull)
            );
            setField(issue, "watcherName",
                asanaTask.followers.stream()
                    .map(follower -> follower.email)
                    .map(context::youTrackUserFromEmail)
                    .filter(Objects::nonNull)
            );
        }

        private static <T> T coalesce(T first, T second) {
            return first != null
                ? first
                : second;
        }

        private void convertCustomFields(WrappedTask wrappedTask, Issue issue, String timeEstimate) {
            Task asanaTask = wrappedTask.getTask();
            if (asanaTask.assignee != null) {
                // https://asana.com/developers/api-reference/tasks says: "null if the task is unassigned"
                setField(issue, "Assignee", context.youTrackUserFromEmail(asanaTask.assignee.email));
            }
            setField(issue, "Due Date", coalesce(asanaTask.dueAt, asanaTask.dueOn));
            setField(issue, "Estimation", timeEstimate);
            if (asanaTask.completedAt != null) {
                setField(issue, "State", "Done");
            }
        }

        private void convertTask(WrappedTask wrappedTask, Issue issue) {
            String updaterLoginName = null;
            List<Comment> comments = issue.getComment();
            for (WrappedComment wrappedComment : wrappedTask.getComments()) {
                var comment = new Comment();
                convertComment(wrappedComment, comment);
                Story story = wrappedComment.getStory();
                switch (story.resourceSubtype) {
                    case "notes_changed":
                        updaterLoginName = context.youTrackUserFromEmail(story.createdBy.email);
                        break;
                    case "comment_added": comments.add(comment); break;
                }
            }

            Task asanaTask = wrappedTask.getTask();
            String taskName = asanaTask.name;
            @Nullable String timeEstimate = null;
            // Asana tasks can have an empty name, but YouTrack tasks cannot have an empty summary
            if (bracketsAsEstimates) {
                var instaganttTimeEstimate = new InstaganttTimeEstimate(asanaTask.name);
                taskName = instaganttTimeEstimate.getTaskName();
                timeEstimate = instaganttTimeEstimate.getTimeEstimateInMinutes();
            }
            if (taskName.isEmpty()) {
                taskName = "(no summary)";
            }

            convertPredefinedFields(wrappedTask, taskName, issue, updaterLoginName);
            convertCustomFields(wrappedTask, issue, timeEstimate);
        }

        private void collectIssues(Issues issuesRoot, IssueUpdates issueUpdatesRoot) {
            List<Issue> oldApiIssues = issuesRoot.getIssue();
            for (WrappedTask wrappedTask : result.getTasks()) {
                Issue issue = new Issue();
                oldApiIssues.add(issue);
                convertTask(wrappedTask, issue);

                Collection<Tag> asanaTags = wrappedTask.getTask().tags;
                if (!asanaTags.isEmpty()) {
                    var newApiIssue = new net.florianschoppmann.issuetracking.youtrack.rest.Issue();
                    newApiIssue.tags = new ArrayList<>();
                    for (Tag asanaTag : asanaTags) {
                        var issueTag = new IssueTag();
                        issueTag.name = asanaTag.name;
                        newApiIssue.tags.add(issueTag);
                    }
                    var issueUpdate = new IssueUpdate();
                    issueUpdate.issueKey = context.youTrackTask(wrappedTask);
                    issueUpdate.issue = newApiIssue;
                    issueUpdatesRoot.issueUpdates.add(issueUpdate);
                }
            }
        }

        private void collectLinks(net.florianschoppmann.issuetracking.youtrack.restold.List linksList) {
            var idToTaskMap = result.getIdToTaskMap();
            List<net.florianschoppmann.issuetracking.youtrack.restold.List.Link> links = linksList.getLink();
            for (WrappedTask wrappedTask : result.getTasks()) {
                Task asanaTask = wrappedTask.getTask();
                BiConsumer<Task, String> linkTarget = (targetTask, relationName) -> {
                    if (targetTask != null) {
                        @Nullable WrappedTask target = idToTaskMap.get(targetTask.id);
                        if (target != null) {
                            var link = new net.florianschoppmann.issuetracking.youtrack.restold.List.Link();
                            link.setSource(context.youTrackTask(target));
                            link.setTarget(context.youTrackTask(wrappedTask));
                            link.setTypeName(relationName);
                            links.add(link);
                        }
                    }
                };

                // Link type "Subtask" is predefined in YouTrack 2018.4
                linkTarget.accept(asanaTask.parent, "Subtask");

                // Link type "Section" is *not* predefined. It's an Asana specialty.
                @Nullable WrappedTask sectionTitle = wrappedTask.getSectionTitle();
                if (sectionTitle != null) {
                    linkTarget.accept(sectionTitle.getTask(), "Section");
                }
            }
        }

        private void collectAttachments(Attachments attachments,  Path attachmentsBasePath) {
            for (WrappedTask task : result.getTasks()) {
                for (WrappedAttachment wrappedAttachment : task.getAttachments()) {
                    Attachment attachment = new Attachment();
                    attachment.taskNumberInProject = task.getNumberInProject();
                    @Nullable WrappedUser creator = wrappedAttachment.getCreatedBy();
                    attachment.authorLogin = creator != null
                        ? context.youTrackUserFromEmail(creator.getUser().email)
                        : UNKNOWN_LOGIN;
                    var asanaAttachment = wrappedAttachment.getAttachment();
                    attachment.created = asanaAttachment.createdAt.getValue();
                    attachment.name = asanaAttachment.name;
                    @Nullable Path downloadPath = wrappedAttachment.getDownloadPath();
                    if (downloadPath != null) {
                        attachment.path = attachmentsBasePath.relativize(downloadPath).toString();
                    } else if (asanaAttachment.viewUrl != null) {
                        attachment.link = asanaAttachment.viewUrl.toString();
                    }
                    attachments.attachments.add(attachment);
                }
            }
        }

        private Result youTrackFromAsanaProject() {
            var issues = new Issues();
            var issueUpdates = new IssueUpdates();
            collectIssues(issues, issueUpdates);

            var links = new net.florianschoppmann.issuetracking.youtrack.restold.List();
            collectLinks(links);

            var attachments = new Attachments();
            collectAttachments(attachments, attachmentsBasePath);

            return new Result(issues, links, attachments, issueUpdates, result.getExportWarnings(), conversionWarnings,
                result.getDownloads());
        }
    }

    private static void verifyEmpty(SortedMap<String, SortedSet<WrappedTask>> map, List<MissingUser> missingUsers) {
        for (Map.Entry<String, SortedSet<WrappedTask>> entry : map.entrySet()) {
            MissingUser missingUser = new MissingUser();
            missingUser.email = entry.getKey();
            for (WrappedTask task : entry.getValue()) {
                var referencingTask = new ReferencingIssue();
                referencingTask.name = task.getTask().name;
                referencingTask.sourceIssueKey = task.getTaskId();
                missingUser.referencingIssues.add(referencingTask);
            }
            missingUsers.add(missingUser);
        }
    }

    /**
     * Returns a new YouTrack Issues object that represents the given Asana export.
     *
     * @param projectId Asana project ID
     * @param attachmentsBasePath path that the attachments of this projects will be stored in
     * @param projectPrefix Prefix for the YouTrack project. The should not include a hyphen (-).
     * @param emailToLoginNameMap Map from email addresses to login names. The email addresses must be all lower case.
     * @param options options for the conversion
     * @throws IllegalArgumentException if {@code emailToLoginNameMap} contains a key where the email address is not
     *     equal to {@link String#toLowerCase()} applied it
     */
    public Result youTrackFromAsanaProject(String projectId, Path attachmentsBasePath, String projectPrefix,
            Map<String, String> emailToLoginNameMap, Options options) throws IOException {
        Optional<String> invalidEmail
            = emailToLoginNameMap.keySet().stream().filter(email -> !email.equals(email.toLowerCase())).findAny();
        if (invalidEmail.isPresent()) {
            throw new IllegalArgumentException(String.format(
                "Expected all lower-case emails, but got \"%s\".", invalidEmail.get()));
        }

        var exportOptions = new Export.Options(
            Arrays.asList("completed_at", "due_at", "due_on", "modified_at", "tags.name"),
            Collections.singleton("is_edited"),
            options.startId
        );
        Export.Result result = export.getTasks(projectId, attachmentsBasePath, exportOptions);

        // The following could alternatively be expressed with streams, but here Collectors.toMap et al. would just make
        // it harder to read the code.
        SortedMap<String, SortedSet<WrappedTask>> requiredEmails = new TreeMap<>();
        for (var entry : result.emailToOccurrenceMap().entrySet()) {
            String email = entry.getKey();
            if (requiredEmails.put(email.toLowerCase(), entry.getValue()) != null) {
                // We trust that Asana does not allow creating two users that have the same email address after
                // converting to lower case
                throw new IllegalStateException(String.format("Email address %s should not be case sensitive.", email));
            }
        }
        requiredEmails.keySet().removeAll(emailToLoginNameMap.keySet());
        var conversionWarnings = new ConversionWarnings();
        verifyEmpty(requiredEmails, conversionWarnings.missingUsers);

        Request request = new Request(result, conversionWarnings, attachmentsBasePath,
            new LazyContextImpl(result, projectPrefix, emailToLoginNameMap), options);
        return request.youTrackFromAsanaProject();
    }

    private static class LazyContextImpl implements LazyContext {
        private final String projectPrefix;
        private final Map<String, String> emailToLoginNameMap;
        private final SortedMap<String, WrappedUser> idToUserMap;
        private final SortedMap<String, WrappedTask> idToTaskMap;

        private LazyContextImpl(Export.Result result, String projectPrefix, Map<String, String> emailToLoginNameMap) {
            this.projectPrefix = projectPrefix;
            this.emailToLoginNameMap = emailToLoginNameMap;
            idToUserMap = result.getIdToUserMap();
            idToTaskMap = result.getIdToTaskMap();
        }

        private String youTrackTask(WrappedTask task) {
            return projectPrefix + '-' + task.getNumberInProject();
        }

        private @Nullable String youTrackTaskFromId(String asanaTaskId) {
            @Nullable WrappedTask task = idToTaskMap.get(asanaTaskId);
            return task != null
                ? youTrackTask(task)
                : null;
        }

        private @Nullable String youTrackUserFromEmail(String email) {
            return emailToLoginNameMap.getOrDefault(email.toLowerCase(), UNKNOWN_LOGIN);
        }

        private @Nullable String youTrackUserFromId(String asanaUserId) {
            @Nullable WrappedUser user = idToUserMap.get(asanaUserId);
            return user != null
                ? ('@' + youTrackUserFromEmail(user.getUser().email))
                : null;
        }

        @Override
        public String stringValueOf(Object object) {
            if (!(object instanceof AsanaReference)) {
                return String.valueOf(object);
            }

            AsanaReference reference = (AsanaReference) object;
            String value = null;
            switch (reference.getType()) {
                case "task": value = youTrackTaskFromId(reference.getGid()); break;
                case "user": value = youTrackUserFromId(reference.getGid()); break;
            }
            return value != null
                ? value
                : reference.getHref();
        }
    }

    public static final class Options {
        private final boolean estimatesInBrackets;
        private final int startId;

        public Options(boolean estimatesInBrackets, int startId) {
            this.estimatesInBrackets = estimatesInBrackets;
            this.startId = startId;
        }
    }

    public static final class Result {
        private final Issues issues;
        private final net.florianschoppmann.issuetracking.youtrack.restold.List links;
        private final Attachments attachments;
        private final IssueUpdates issueUpdates;
        private final AsanaExportWarnings exportWarnings;
        private final ConversionWarnings conversionWarnings;
        private final List<CompletableFuture<Path>> downloads;

        private Result(Issues issues, net.florianschoppmann.issuetracking.youtrack.restold.List links, Attachments attachments,
                IssueUpdates issueUpdates, AsanaExportWarnings exportWarnings, ConversionWarnings conversionWarnings,
                List<CompletableFuture<Path>> downloads) {
            this.issues = issues;
            this.links = links;
            this.attachments = attachments;
            this.issueUpdates = issueUpdates;
            this.exportWarnings = exportWarnings;
            this.conversionWarnings = conversionWarnings;
            this.downloads = downloads;
        }

        public Issues getIssues() {
            return issues;
        }

        public net.florianschoppmann.issuetracking.youtrack.restold.List getLinks() {
            return links;
        }

        public Attachments getAttachments() {
            return attachments;
        }

        public IssueUpdates getIssueUpdates() {
            return issueUpdates;
        }

        public AsanaExportWarnings getExportWarnings() {
            return exportWarnings;
        }

        public ConversionWarnings getConversionWarnings() {
            return conversionWarnings;
        }

        public List<CompletableFuture<Path>> getDownloads() {
            return Collections.unmodifiableList(downloads);
        }
    }
}
