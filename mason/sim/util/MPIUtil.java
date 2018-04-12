package sim.util;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.IntStream;

import mpi.*;

import sim.field.DPartition;

// Utility class that serialize/exchange/deserialize objects using MPI
public class MPIUtil {

	// Serialize a Serializable using Java's builtin serialization and return the byte array
	private static byte[] serialize(Serializable obj) {
		byte[] buf = null;

		try (
			    ByteArrayOutputStream out = new ByteArrayOutputStream();
			    ObjectOutputStream os = new ObjectOutputStream(out)
			) {
			os.writeObject(obj);
			os.flush();
			buf = out.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		return buf;
	}

	// Serialize each Serializable in objs using Java's builtin serialization
	// Concatenate their individual byte array together and return the final byte array
	// The length of each byte array will be returned through count array
	private static byte[] serialize(Serializable[] objs, int[] count) {
		byte[] buf = null;

		byte[][] objsBuf = Arrays.stream(objs).map(obj -> serialize(obj)).toArray(s -> new byte[s][]);
		for (int i = 0; i < objs.length; i++)
			count[i] = objsBuf[i].length;

		try (ByteArrayOutputStream concat = new ByteArrayOutputStream()) {
			for (byte[] objBuf : objsBuf)
				concat.write(objBuf);
			concat.flush();
			buf = concat.toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		return buf;
	}

	// Deserialize the object of given type T that is stored in [pos, pos + len) in buf
	private static <T extends Serializable> T deserialize(byte[] buf, int pos, int len) {
		T obj = null;

		try (
			    ByteArrayInputStream in = new ByteArrayInputStream(buf, pos, len);
			    ObjectInputStream is = new ObjectInputStream(in);
			) {
			obj = (T)is.readObject();
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		return obj;
	}

	// Compute the displacement according to the count
	private static int[] getDispl(int[] count) {
		return IntStream.range(0, count.length)
		       .map(x -> Arrays.stream(count).limit(x).sum())
		       .toArray();
	}

	// Each LP sends the sendObj to dst
	// dst will return an ArrayList of np objects of type T
	// others will return an empty ArrayList
	public static <T extends Serializable> ArrayList<T> gather(DPartition p, T sendObj, int dst) throws MPIException {
		int np = p.getNumProc();
		int pid = p.getPid();
		int[] dstDispl, dstCount = new int[np];
		byte[] dstBuf, srcBuf;
		ArrayList<T> recvObjs = new ArrayList();

		srcBuf = serialize(sendObj);

		p.getCommunicator().gather(new int[] {srcBuf.length}, 1, MPI.INT, dstCount, 1, MPI.INT, dst);

		dstBuf = new byte[Arrays.stream(dstCount).sum()];
		dstDispl = getDispl(dstCount);

		p.getCommunicator().gatherv(srcBuf, srcBuf.length, MPI.BYTE, dstBuf, dstCount, dstDispl, MPI.BYTE, dst);

		if (pid == dst)
			for (int i = 0; i < np; i++)
				if (i == pid)
					recvObjs.add(sendObj);
				else
					recvObjs.add(deserialize(dstBuf, dstDispl[i], dstCount[i]));

		return recvObjs;
	}

	// Each LP contributes the sendObj
	// All the LPs will receive all the sendObjs from all the LPs returned in the ArrayList
	public static <T extends Serializable> ArrayList<T> allGather(DPartition p, T sendObj) throws MPIException {
		int np = p.getNumProc();
		int pid = p.getPid();
		int[] dstDispl, dstCount = new int[np];
		byte[] dstBuf, srcBuf;
		ArrayList<T> recvObjs = new ArrayList();

		srcBuf = serialize(sendObj);
		dstCount[pid] = srcBuf.length;

		p.getCommunicator().allGather(dstCount, 1, MPI.INT);

		dstBuf = new byte[Arrays.stream(dstCount).sum()];
		dstDispl = getDispl(dstCount);

		p.getCommunicator().allGatherv(srcBuf, srcBuf.length, MPI.BYTE, dstBuf, dstCount, dstDispl, MPI.BYTE);

		for (int i = 0; i < np; i++)
			if (i == pid)
				recvObjs.add(sendObj);
			else
				recvObjs.add(deserialize(dstBuf, dstDispl[i], dstCount[i]));

		return recvObjs;
	}

	// Each LP sends and receives one object to/from each of its neighbors 
	// in the order that is defined in partition scheme
	public static <T extends Serializable> ArrayList<T> neighborAllToAll(DPartition p, T[] sendObjs) throws MPIException {
		int nc = sendObjs.length;
		int[] srcDispl, srcCount = new int[nc];
		int[] dstDispl, dstCount = new int[nc];
		byte[] srcBuf, dstBuf;
		ArrayList<T> recvObjs = new ArrayList();

		srcBuf = serialize(sendObjs, srcCount);
		srcDispl = getDispl(srcCount);

		p.getCommunicator().neighborAllToAll(srcCount, 1, MPI.INT, dstCount, 1, MPI.INT);

		dstBuf = new byte[Arrays.stream(dstCount).sum()];
		dstDispl = getDispl(dstCount);

		p.getCommunicator().neighborAllToAllv(srcBuf, srcCount, srcDispl, MPI.BYTE, dstBuf, dstCount, dstDispl, MPI.BYTE);

		for (int i = 0; i < nc; i++)
			recvObjs.add(deserialize(dstBuf, dstDispl[i], dstCount[i]));

		return recvObjs;
	}

	public static void main(String[] args) throws MPIException, IOException {
		MPI.Init(args);

		sim.field.DNonUniformPartition p = sim.field.DNonUniformPartition.getPartitionScheme(new int[] {10, 10}, true);
		p.initUniformly(null);
		p.commit();

		int[] nids = p.getNeighborIds();
		Integer[] t = new Integer[nids.length];
		for (int i = 0; i < nids.length; i++)
			t[i] = p.getPid() * 10 + nids[i];
		final int dst = 0;

		ArrayList<Integer[]> res = MPIUtil.<Integer[]>gather(p, t, dst);

		MPITest.execInOrder(x -> {
			System.out.println("gather to dst " + dst + " PID " + x);
			for (Integer[] r : res)
				for (Integer i : r)
					System.out.println(i);
		}, 100);

		ArrayList<Integer[]> res2 = MPIUtil.<Integer[]>allGather(p, t);

		MPITest.execInOrder(x -> {
			System.out.println("allGather PID " + x);
			for (Integer[] r : res2)
				for (Integer i : r)
					System.out.println(i);
		}, 100);

		ArrayList<Integer> res3 = MPIUtil.<Integer>neighborAllToAll(p, t);

		MPITest.execInOrder(x -> {
			System.out.println("neighborAllToAll PID " + x);
			for (Integer i : res3)
				System.out.println(i);
		}, 100);

		MPI.Finalize();
	}
}