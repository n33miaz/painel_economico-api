package br.com.economize.model;

/**
 * Plano da conta (V23). FREE vê anúncios; PLUS não vê, enquanto estiver
 * vigente — a regra de vigência é {@link User#isPlus()}, não este enum.
 */
public enum Plan {
    FREE,
    PLUS
}
