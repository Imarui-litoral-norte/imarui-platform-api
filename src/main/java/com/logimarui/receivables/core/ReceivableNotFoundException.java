package com.logimarui.receivables.core;

public class ReceivableNotFoundException extends RuntimeException {
    public ReceivableNotFoundException() { super("Título elegível não encontrado na carteira atual."); }
}

