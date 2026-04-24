package it.cnr.isti.labsedc.concern.probes.core;

public record IngestResult(Status status, String eventId, String error) {

    public enum Status { SENT, BUFFERED, FAILED }

    public static IngestResult sent(String id)      { return new IngestResult(Status.SENT,     id,   null); }
    public static IngestResult buffered(String id)  { return new IngestResult(Status.BUFFERED, id,   null); }
    public static IngestResult failed(String error) { return new IngestResult(Status.FAILED,   null, error); }

    public int httpStatus() {
        return switch (status) {
            case SENT     -> 200;
            case BUFFERED -> 202;
            case FAILED   -> 503;
        };
    }
}
