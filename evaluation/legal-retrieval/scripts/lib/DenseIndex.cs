using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

public sealed class RetrievalHit {
    public string ChunkId { get; set; }
    public string UnitId { get; set; }
    public double Score { get; set; }
}

public sealed class LegalDenseIndex {
    private readonly Dictionary<string, float[]> vectors = new Dictionary<string, float[]>(StringComparer.Ordinal);
    private readonly Dictionary<string, string> units = new Dictionary<string, string>(StringComparer.Ordinal);
    public int Count { get { return vectors.Count; } }
    public bool Contains(string id) { return vectors.ContainsKey(id); }

    private static float[] Normalize(float[] vector) {
        if (vector.Length != 768) throw new InvalidDataException("Expected 768 dimensions.");
        double norm = 0;
        foreach (float value in vector) {
            if (float.IsNaN(value) || float.IsInfinity(value)) throw new InvalidDataException("Non-finite vector.");
            norm += (double)value * value;
        }
        if (norm <= 0) throw new InvalidDataException("Zero vector.");
        var result = new float[vector.Length];
        norm = Math.Sqrt(norm);
        for (int i = 0; i < result.Length; i++) result[i] = (float)(vector[i] / norm);
        return result;
    }

    public void WriteBatch(string path, string[] ids, float[][] values) {
        if (ids.Length != values.Length) throw new InvalidDataException("Embedding count mismatch.");
        var normalized = values.Select(Normalize).ToArray();
        string temporary = path + ".tmp";
        using (var writer = new BinaryWriter(File.Open(temporary, FileMode.Create, FileAccess.Write))) {
            writer.Write("MOLELAW-VECTORS-1");
            writer.Write(ids.Length);
            for (int i = 0; i < ids.Length; i++) {
                writer.Write(ids[i]);
                writer.Write(normalized[i].Length);
                foreach (float value in normalized[i]) writer.Write(value);
            }
        }
        File.Move(temporary, path);
        for (int i = 0; i < ids.Length; i++) vectors.Add(ids[i], normalized[i]);
    }

    public void ReadBatch(string path) {
        using (var reader = new BinaryReader(File.OpenRead(path))) {
            if (reader.ReadString() != "MOLELAW-VECTORS-1") throw new InvalidDataException("Unknown vector cache.");
            int count = reader.ReadInt32();
            if (count < 1 || count > 64) throw new InvalidDataException("Invalid batch size.");
            for (int i = 0; i < count; i++) {
                string id = reader.ReadString();
                int dimensions = reader.ReadInt32();
                if (dimensions != 768) throw new InvalidDataException("Invalid dimensions.");
                var vector = new float[dimensions];
                for (int j = 0; j < dimensions; j++) vector[j] = reader.ReadSingle();
                vectors.Add(id, Normalize(vector));
            }
            if (reader.BaseStream.Position != reader.BaseStream.Length) throw new InvalidDataException("Trailing cache bytes.");
        }
    }

    public void SetUnit(string chunkId, string unitId) {
        if (!vectors.ContainsKey(chunkId)) throw new InvalidDataException("Missing vector: " + chunkId);
        units.Add(chunkId, unitId);
    }

    public RetrievalHit[] Rank(float[] query, int limit) {
        query = Normalize(query);
        var best = new Dictionary<string, RetrievalHit>(StringComparer.Ordinal);
        foreach (var pair in vectors) {
            double dot = 0;
            for (int i = 0; i < query.Length; i++) dot += (double)query[i] * pair.Value[i];
            string unitId = units[pair.Key];
            RetrievalHit previous;
            if (!best.TryGetValue(unitId, out previous) || dot > previous.Score ||
                (dot == previous.Score && StringComparer.Ordinal.Compare(pair.Key, previous.ChunkId) < 0)) {
                best[unitId] = new RetrievalHit { ChunkId = pair.Key, UnitId = unitId, Score = dot };
            }
        }
        return best.Values.OrderByDescending(x => x.Score).ThenBy(x => x.UnitId, StringComparer.Ordinal).Take(limit).ToArray();
    }
}
