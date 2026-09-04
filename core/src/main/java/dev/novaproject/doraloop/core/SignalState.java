package dev.novaproject.doraloop.core;

/**
 * The three states a signal may render.
 *
 * <p>UNOBSERVED is the important one. A metric computed from zero observations
 * is not OK and it is not zero -- it is unobserved. Rendering it green is how
 * a dashboard reports health for something it never measured.
 */
public enum SignalState {
    OK,
    DEGRADED,
    UNOBSERVED
}
