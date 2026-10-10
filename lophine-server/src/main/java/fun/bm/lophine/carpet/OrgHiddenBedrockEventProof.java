package fun.bm.lophine.carpet;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Original queued event identity and a true replacement proof, independent of callback timing.
 */
final class OrgHiddenBedrockEventProof<E> {
    private final Set<E> queued = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Ticket> tickets = Collections.newSetFromMap(new IdentityHashMap<>());

    final class Ticket {
        private final Set<E> events = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed, replaced;

        void replaced() {
            synchronized (OrgHiddenBedrockEventProof.this) {
                if (!closed) replaced = true;
            }
        }

        void close() {
            synchronized (OrgHiddenBedrockEventProof.this) {
                closed = true;
                prune();
            }
        }
    }

    synchronized Ticket arm() {
        var ticket = new Ticket();
        ticket.events.addAll(queued);
        tickets.add(ticket);
        return ticket;
    }

    synchronized void queued(E event) {
        queued.add(event);
        for (var ticket : tickets) if (!ticket.closed) ticket.events.add(event);
    }

    synchronized boolean defer(E event) {
        queued(event);
        return tickets.stream().anyMatch(ticket -> !ticket.closed);
    }

    synchronized boolean ready(E event) {
        return tickets.stream().anyMatch(ticket -> ticket.closed && ticket.replaced && ticket.events.contains(event));
    }

    synchronized void consumed(E event) {
        queued.remove(event);
        for (var ticket : tickets) ticket.events.remove(event);
        prune();
    }

    synchronized boolean empty() {
        prune();
        return queued.isEmpty() && tickets.isEmpty();
    }

    private void prune() {
        tickets.removeIf(ticket -> ticket.closed && (!ticket.replaced || ticket.events.isEmpty()));
    }
}
