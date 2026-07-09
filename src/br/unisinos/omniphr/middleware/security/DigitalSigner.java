package br.unisinos.omniphr.middleware.security;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Digital Signer component of the Security and Privacy Module. Responsible
 * for the digital signature of datablocks on transmission and storage: each
 * user has a digital signature assigned to every datablock they inform, so
 * that any copy can be verified as unchanged and produced by the signer.
 */
public class DigitalSigner {

    private static final String ALGORITHM = "SHA256withRSA";

    public byte[] sign(String data, PrivateKey signerKey) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(signerKey);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String data, byte[] signature, PublicKey signerPublicKey) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(signerPublicKey);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
