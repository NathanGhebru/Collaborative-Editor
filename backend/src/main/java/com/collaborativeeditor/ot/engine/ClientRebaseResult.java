package com.collaborativeeditor.ot.engine;

import java.util.List;

import com.collaborativeeditor.ot.model.ClientOperation;
import com.collaborativeeditor.ot.model.Operation;

/**
 * Result container for client-side multi-operation queue rebase.
 *
 * @param transformedInFlight The transformed in-flight operation (or null if none was in flight).
 * @param transformedBuffered The list of transformed buffered operations.
 * @param transformedRemoteForOptimistic The transformed remote operation to apply to the local optimistic document.
 */
public record ClientRebaseResult(
    ClientOperation transformedInFlight,
    List<ClientOperation> transformedBuffered,
    Operation transformedRemoteForOptimistic
) {}

