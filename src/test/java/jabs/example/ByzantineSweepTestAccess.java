package jabs.example;

/** Ponte de teste: expoe o metodo package-private de {@link ByzantineSweep} a outros pacotes de teste. */
public final class ByzantineSweepTestAccess {
    private ByzantineSweepTestAccess() { }

    public static boolean conta(boolean comAutoentrega, double selfBytes) {
        return ByzantineSweep.contaAutoentrega(comAutoentrega, selfBytes);
    }
}
