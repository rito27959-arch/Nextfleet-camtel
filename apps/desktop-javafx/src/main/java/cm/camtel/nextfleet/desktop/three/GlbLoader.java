package cm.camtel.nextfleet.desktop.three;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Chargeur glTF 2.0 binaire (GLB) vers JavaFX 3D, sans dépendance tierce.
 * Pris en charge : hiérarchie de nœuds (matrice ou TRS), triangles, positions, normales, coordonnées de texture,
 * index 8/16/32 bits, matériaux métal/rugosité (couleur de base, texture de base, émission approchée).
 * Non pris en charge (ignoré sans erreur) : animations, peau, morphs, Draco/Meshopt, KTX2, extensions de matériaux.
 * JavaFX est en Y vers le bas : la racine est tournée de 180° autour de X (repère direct conservé).
 */
public final class GlbLoader {

    public record Model(Group root, double[] min, double[] max, int triangles) { }

    public static final class GlbException extends IOException {
        private static final long serialVersionUID = 1L;

        public GlbException(String message) {
            super(message);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private JsonNode gltf;
    private ByteBuffer bin;
    private final Map<Integer, PhongMaterial> materials = new HashMap<>();
    private final double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
    private final double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    private int triangles;

    public Model load(byte[] glb) throws GlbException {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        if (glb.length < 20 || b.getInt(0) != 0x46546C67) throw new GlbException("Fichier GLB invalide (en-tête)");
        if (b.getInt(4) != 2) throw new GlbException("Version glTF non prise en charge : " + b.getInt(4));
        int pos = 12;
        while (pos + 8 <= glb.length) {
            int len = b.getInt(pos);
            int type = b.getInt(pos + 4);
            if (pos + 8 + len > glb.length) throw new GlbException("Bloc GLB tronqué");
            if (type == 0x4E4F534A) {
                try {
                    gltf = json.readTree(new String(glb, pos + 8, len, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new GlbException("JSON glTF illisible");
                }
            } else if (type == 0x004E4942) {
                bin = ByteBuffer.wrap(glb, pos + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            pos += 8 + len;
        }
        if (gltf == null) throw new GlbException("Bloc JSON absent");
        for (JsonNode ext : gltf.path("extensionsRequired")) {
            String e = ext.asText();
            if (e.startsWith("KHR_draco") || e.startsWith("EXT_meshopt") || e.equals("KHR_texture_basisu")) {
                throw new GlbException("Extension requise non prise en charge : " + e);
            }
        }
        Group group = new Group();
        int scene = gltf.path("scene").asInt(0);
        JsonNode roots = gltf.path("scenes").path(scene).path("nodes");
        for (JsonNode n : roots) visit(n.asInt(), identity(), group, 0);
        Group root = new Group(group);
        root.getTransforms().add(new javafx.scene.transform.Rotate(180, javafx.scene.transform.Rotate.X_AXIS));
        return new Model(root, min.clone(), max.clone(), triangles);
    }

    private void visit(int index, double[] parent, Group out, int depth) throws GlbException {
        if (depth > 64) throw new GlbException("Hiérarchie de nœuds trop profonde");
        JsonNode node = gltf.path("nodes").path(index);
        double[] world = multiply(parent, local(node));
        if (node.has("mesh")) {
            for (JsonNode prim : gltf.path("meshes").path(node.path("mesh").asInt()).path("primitives")) {
                MeshView mv = primitive(prim, world);
                if (mv != null) out.getChildren().add(mv);
            }
        }
        for (JsonNode c : node.path("children")) visit(c.asInt(), world, out, depth + 1);
    }

    private MeshView primitive(JsonNode prim, double[] m) throws GlbException {
        if (prim.path("mode").asInt(4) != 4) return null;   // triangles seulement
        JsonNode attrs = prim.path("attributes");
        if (!attrs.has("POSITION")) return null;
        float[] p = floats(attrs.path("POSITION").asInt(), 3);
        float[] n = attrs.has("NORMAL") ? floats(attrs.path("NORMAL").asInt(), 3) : null;
        float[] t = attrs.has("TEXCOORD_0") ? floats(attrs.path("TEXCOORD_0").asInt(), 2) : null;
        int vertexCount = p.length / 3;
        int[] idx = prim.has("indices") ? ints(prim.path("indices").asInt()) : sequence(vertexCount);

        float[] wp = new float[p.length];
        for (int i = 0; i < vertexCount; i++) {
            double x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2];
            double wx = m[0] * x + m[4] * y + m[8] * z + m[12];
            double wy = m[1] * x + m[5] * y + m[9] * z + m[13];
            double wz = m[2] * x + m[6] * y + m[10] * z + m[14];
            wp[3 * i] = (float) wx;
            wp[3 * i + 1] = (float) wy;
            wp[3 * i + 2] = (float) wz;
            min[0] = Math.min(min[0], wx); min[1] = Math.min(min[1], wy); min[2] = Math.min(min[2], wz);
            max[0] = Math.max(max[0], wx); max[1] = Math.max(max[1], wy); max[2] = Math.max(max[2], wz);
        }
        TriangleMesh mesh = new TriangleMesh(n != null ? VertexFormat.POINT_NORMAL_TEXCOORD : VertexFormat.POINT_TEXCOORD);
        mesh.getPoints().setAll(wp);
        if (n != null) {
            float[] wn = new float[n.length];
            for (int i = 0; i < n.length / 3; i++) {
                double x = n[3 * i], y = n[3 * i + 1], z = n[3 * i + 2];
                double nx = m[0] * x + m[4] * y + m[8] * z, ny = m[1] * x + m[5] * y + m[9] * z, nz = m[2] * x + m[6] * y + m[10] * z;
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (l < 1e-12) l = 1;
                wn[3 * i] = (float) (nx / l);
                wn[3 * i + 1] = (float) (ny / l);
                wn[3 * i + 2] = (float) (nz / l);
            }
            mesh.getNormals().setAll(wn);
        }
        if (t != null) mesh.getTexCoords().setAll(t);
        else mesh.getTexCoords().setAll(0, 0);
        int stride = n != null ? 3 : 2;
        int[] faces = new int[idx.length * stride];
        for (int i = 0; i < idx.length; i++) {
            int v = idx[i];
            if (v < 0 || v >= vertexCount) throw new GlbException("Index de sommet hors limites");
            faces[i * stride] = v;
            if (n != null) {
                faces[i * stride + 1] = v;
                faces[i * stride + 2] = t != null ? v : 0;
            } else {
                faces[i * stride + 1] = t != null ? v : 0;
            }
        }
        mesh.getFaces().setAll(faces);
        triangles += idx.length / 3;
        MeshView view = new MeshView(mesh);
        view.setCullFace(gltf.path("materials").path(prim.path("material").asInt(-1)).path("doubleSided").asBoolean(false)
                ? CullFace.NONE : CullFace.BACK);
        view.setMaterial(material(prim.path("material").asInt(-1)));
        return view;
    }

    private PhongMaterial material(int index) {
        return materials.computeIfAbsent(index, i -> {
            PhongMaterial pm = new PhongMaterial(Color.gray(0.8));
            if (i < 0) return pm;
            JsonNode mat = gltf.path("materials").path(i);
            JsonNode pbr = mat.path("pbrMetallicRoughness");
            JsonNode f = pbr.path("baseColorFactor");
            Color base = f.isArray() ? Color.color(clamp(f.path(0).asDouble(1)), clamp(f.path(1).asDouble(1)),
                    clamp(f.path(2).asDouble(1)), clamp(f.path(3).asDouble(1))) : Color.WHITE;
            JsonNode e = mat.path("emissiveFactor");
            if (e.isArray()) {   // émission approchée : éclaircit la couleur de base
                base = base.interpolate(Color.color(clamp(e.path(0).asDouble()), clamp(e.path(1).asDouble()), clamp(e.path(2).asDouble())), 0.6);
            }
            pm.setDiffuseColor(base);
            double metal = pbr.path("metallicFactor").asDouble(1);
            double rough = pbr.path("roughnessFactor").asDouble(1);
            pm.setSpecularColor(Color.gray(clamp(0.15 + 0.55 * (1 - rough) * (0.5 + 0.5 * metal))));
            pm.setSpecularPower(8 + 120 * (1 - rough));
            Image tex = texture(pbr.path("baseColorTexture").path("index").asInt(-1));
            if (tex != null) pm.setDiffuseMap(tex);
            return pm;
        });
    }

    private Image texture(int index) {
        if (index < 0) return null;
        JsonNode img = gltf.path("images").path(gltf.path("textures").path(index).path("source").asInt(-1));
        if (!img.has("bufferView")) return null;   // images externes non chargées (GLB autonome attendu)
        String mime = img.path("mimeType").asText("");
        if (!mime.equals("image/png") && !mime.equals("image/jpeg")) return null;
        byte[] data = view(img.path("bufferView").asInt());
        Image i = new Image(new ByteArrayInputStream(data));
        return i.isError() ? null : i;
    }

    // ------------------------------------------------------------------ accesseurs

    private byte[] view(int bv) {
        JsonNode v = gltf.path("bufferViews").path(bv);
        int off = v.path("byteOffset").asInt(0);
        int len = v.path("byteLength").asInt();
        byte[] out = new byte[len];
        bin.get(off, out);
        return out;
    }

    private float[] floats(int accessor, int comps) throws GlbException {
        JsonNode a = gltf.path("accessors").path(accessor);
        int count = a.path("count").asInt();
        int type = a.path("componentType").asInt();
        boolean normalized = a.path("normalized").asBoolean(false);
        float[] out = new float[count * comps];
        if (!a.has("bufferView")) return out;   // accesseur sans données : zéros (spécification glTF)
        JsonNode v = gltf.path("bufferViews").path(a.path("bufferView").asInt());
        int size = switch (type) {
            case 5126 -> 4;
            case 5123, 5122 -> 2;
            case 5121, 5120 -> 1;
            default -> throw new GlbException("Type de composante non pris en charge : " + type);
        };
        int stride = v.path("byteStride").asInt(size * comps);
        int base = v.path("byteOffset").asInt(0) + a.path("byteOffset").asInt(0);
        if (base + (long) stride * (count - 1) + (long) size * comps > bin.limit()) throw new GlbException("Accesseur hors du tampon");
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < comps; c++) {
                int at = base + i * stride + c * size;
                out[i * comps + c] = switch (type) {
                    case 5126 -> bin.getFloat(at);
                    case 5123 -> normalized ? (bin.getShort(at) & 0xFFFF) / 65535f : bin.getShort(at) & 0xFFFF;
                    case 5122 -> normalized ? Math.max(bin.getShort(at) / 32767f, -1f) : bin.getShort(at);
                    case 5121 -> normalized ? (bin.get(at) & 0xFF) / 255f : bin.get(at) & 0xFF;
                    default -> normalized ? Math.max(bin.get(at) / 127f, -1f) : bin.get(at);
                };
            }
        }
        return out;
    }

    private int[] ints(int accessor) throws GlbException {
        JsonNode a = gltf.path("accessors").path(accessor);
        int count = a.path("count").asInt();
        int type = a.path("componentType").asInt();
        JsonNode v = gltf.path("bufferViews").path(a.path("bufferView").asInt());
        int base = v.path("byteOffset").asInt(0) + a.path("byteOffset").asInt(0);
        int size = type == 5125 ? 4 : type == 5123 ? 2 : 1;
        if (base + (long) size * count > bin.limit()) throw new GlbException("Index hors du tampon");
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int at = base + i * size;
            out[i] = switch (type) {
                case 5125 -> bin.getInt(at);
                case 5123 -> bin.getShort(at) & 0xFFFF;
                case 5121 -> bin.get(at) & 0xFF;
                default -> throw new GlbException("Type d'index non pris en charge : " + type);
            };
        }
        return out;
    }

    private static int[] sequence(int n) {
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = i;
        return s;
    }

    // ------------------------------------------------------------------ matrices (colonne majeure, comme glTF)

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] local(JsonNode node) {
        JsonNode mat = node.path("matrix");
        if (mat.isArray() && mat.size() == 16) {
            double[] m = new double[16];
            for (int i = 0; i < 16; i++) m[i] = mat.path(i).asDouble();
            return m;
        }
        JsonNode t = node.path("translation"), r = node.path("rotation"), s = node.path("scale");
        double tx = t.path(0).asDouble(0), ty = t.path(1).asDouble(0), tz = t.path(2).asDouble(0);
        double qx = r.path(0).asDouble(0), qy = r.path(1).asDouble(0), qz = r.path(2).asDouble(0), qw = r.path(3).asDouble(1);
        double sx = s.path(0).asDouble(1), sy = s.path(1).asDouble(1), sz = s.path(2).asDouble(1);
        double xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wx = qw * qx, wy = qw * qy, wz = qw * qz;
        return new double[]{
                (1 - 2 * (yy + zz)) * sx, 2 * (xy + wz) * sx, 2 * (xz - wy) * sx, 0,
                2 * (xy - wz) * sy, (1 - 2 * (xx + zz)) * sy, 2 * (yz + wx) * sy, 0,
                2 * (xz + wy) * sz, 2 * (yz - wx) * sz, (1 - 2 * (xx + yy)) * sz, 0,
                tx, ty, tz, 1};
    }

    static double[] multiply(double[] a, double[] b) {
        double[] r = new double[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[col * 4 + k];
                r[col * 4 + row] = s;
            }
        }
        return r;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
