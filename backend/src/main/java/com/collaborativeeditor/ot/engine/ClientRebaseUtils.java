package com.collaborativeeditor.ot.engine;

import com.collaborativeeditor.ot.model.ClientOperation;
import com.collaborativeeditor.ot.model.Operation;
import com.collaborativeeditor.ot.model.OperationKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements client-side operation rebase against incoming remote canonical operations (ADR-001 Section 24).
 */
public final class ClientRebaseUtils {

    private ClientRebaseUtils() {}

    /**
     * Rebases client in-flight and buffered operations against an incoming canonical remote operation.
     *
     * @param remoteOp The incoming canonical remote operation.
     * @param inFlight The current in-flight operation awaiting ack (null if synchronized).
     * @param buffered The list of locally queued buffered operations (non-null, may be empty).
     * @return ClientRebaseResult containing transformed in-flight, transformed buffer, and transformed remote op.
     */
    public static ClientRebaseResult rebase(
        ClientOperation remoteOp,
        ClientOperation inFlight,
        List<ClientOperation> buffered
    ) {
        Objects.requireNonNull(remoteOp, "remoteOp must not be null");
        List<ClientOperation> bufferList = buffered != null ? buffered : Collections.emptyList();

        // State 1: Synchronized (no in-flight, no buffer)
        if (inFlight == null && bufferList.isEmpty()) {
            return new ClientRebaseResult(null, Collections.emptyList(), remoteOp.operation());
        }

        Operation rCurrent = remoteOp.operation();
        OperationKey keyR = remoteOp.getOperationKey();

        ClientOperation newInFlight = null;

        // Rebase against inFlight if present
        if (inFlight != null) {
            OperationKey keyA = inFlight.getOperationKey();
            Operation inFlightOp = inFlight.operation();

            Operation rNext = OtEngine.transform(rCurrent, inFlightOp, keyR, keyA);
            Operation inFlightPrime = OtEngine.transform(inFlightOp, rCurrent, keyA, keyR);

            newInFlight = inFlight.withOperation(inFlightPrime);
            rCurrent = rNext;
        }

        // Rebase through local buffer
        List<ClientOperation> newBuffer = new ArrayList<>(bufferList.size());
        for (ClientOperation bufferedOp : bufferList) {
            OperationKey keyB = bufferedOp.getOperationKey();
            Operation bOp = bufferedOp.operation();

            Operation rNext = OtEngine.transform(rCurrent, bOp, keyR, keyB);
            Operation bPrime = OtEngine.transform(bOp, rCurrent, keyB, keyR);

            newBuffer.add(bufferedOp.withOperation(bPrime));
            rCurrent = rNext;
        }

        return new ClientRebaseResult(newInFlight, Collections.unmodifiableList(newBuffer), rCurrent);
    }
}

