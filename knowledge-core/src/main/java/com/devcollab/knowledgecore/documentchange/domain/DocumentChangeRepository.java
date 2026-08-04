package com.devcollab.knowledgecore.documentchange.domain;

import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.ChangeRequest;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.BindingProposal;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Evidence;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.ListItem;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Operation;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentChangeRepository {

    ChangeRequest saveRequest(ChangeRequest request);

    Operation saveOperation(Operation operation);

    BindingProposal saveBindingProposal(BindingProposal proposal);

    Evidence saveEvidence(Evidence evidence);

    Optional<ChangeRequest> findRequest(UUID workspaceId, UUID requestId);

    Optional<ChangeRequest> findRequestForUpdate(
            UUID workspaceId,
            UUID requestId
    );

    Optional<ChangeRequest> findByClientRequestId(
            UUID workspaceId,
            UUID submittedBy,
            String clientRequestId
    );

    List<Operation> findOperations(UUID requestId);

    List<BindingProposal> findBindingProposals(UUID requestId);

    List<Evidence> findEvidence(UUID requestId);

    long count(UUID workspaceId, Status status);

    long countAll(UUID workspaceId);

    List<ListItem> findPage(
            UUID workspaceId,
            Status status,
            int offset,
            int size,
            boolean ascending
    );

    ChangeRequest decide(
            ChangeRequest request,
            Status status,
            UUID reviewedBy,
            Instant reviewedAt,
            String rejectionReason
    );

    /** Find all change requests whose operations target the given document. */
    List<ChangeRequest> findRequestsAffectingDocument(UUID documentId, int limit);
}
