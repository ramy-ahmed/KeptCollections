/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.killa.kept;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;

/**
 * A Java {@link Collection} that is kept synchronized amongst a
 * {@link ZooKeeper} cluster.
 * 
 * NB: set updates are performed asynchronously via a {@link Watcher}, so there
 * may be a delay between modifying the list and it reflecting the change.
 * 
 */
public class KeptCollection<T> implements Collection<T>, Synchronizable {
    private final SynchronizingWatcher watcher;
    protected final List<T> elements;
    protected final Class<? extends T> elementClass;

    private final ZooKeeper keeper;
    private final String znode;
    private final List<ACL> acl;
    private final CreateMode createMode;

    /**
     * Construct a KeptCollection.
     * 
     * @param elementClass
     *            A {@link Class} representing the class of object that will be
     *            elements of this collection
     * 
     * @param keeper
     *            A {@link ZooKeeper} that is synchronized with
     * 
     * @param znode
     *            A {@link String} containing the znode whose children will be
     *            members of the collection
     * 
     * @param acl
     *            A {@link List} of {@link ACL} containing the access control
     *            lists for child node creation
     * 
     * @param createMode
     *            A {@link CreateMode}, representing the mode of created
     *            children.
     * 
     * @throws KeeperException
     * @throws InterruptedException
     * 
     */
    public KeptCollection(final Class<? extends T> elementClass,
	    final ZooKeeper keeper, final String znode, final List<ACL> acl,
	    final CreateMode createMode) throws KeeperException,
	    InterruptedException {
	this.elements = new ArrayList<T>();
	this.elementClass = elementClass;
	this.keeper = keeper;

	// if the znode doesn't exist, create a permanent znode with that path
	// TODO: change to allow ephemeral znode when ephemeral parents are
	// supported by zookeeper
	try {
	    if (this.keeper.exists(znode, false) == null)
		this.keeper.create(znode, new byte[0], acl,
			CreateMode.PERSISTENT);
	} catch (final KeeperException.NodeExistsException e) {
	    // ignore this exception
	}

	this.znode = znode;
	this.acl = acl;
	if (createMode == CreateMode.PERSISTENT_SEQUENTIAL
		|| createMode == CreateMode.EPHEMERAL_SEQUENTIAL)
	    this.createMode = createMode;
	else if (createMode == CreateMode.PERSISTENT)
	    this.createMode = CreateMode.PERSISTENT_SEQUENTIAL;
	else if (createMode == CreateMode.EPHEMERAL)
	    this.createMode = CreateMode.EPHEMERAL_SEQUENTIAL;
	else
	    throw new InvalidParameterException("unexpected create mode "
		    + createMode.toString());

	this.watcher = new SynchronizingWatcher(this);

	this.synchronize();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void synchronize() throws KeeperException, InterruptedException {
	synchronized (this.elements) {
	    try {
		// clear out the cache and reload it
		this.elements.clear();

		for (final String s : this.keeper.getChildren(this.znode,
			this.watcher))
		    this.elements.add((T) Transformer.bytesToObject(this.keeper
			    .getData(this.znode + '/' + s, false, null),
			    this.elementClass));
	    } catch (final KeeperException.SessionExpiredException e) {
		// ignore it
	    } catch (final Exception e) {
		throw new RuntimeException(e.getClass().getSimpleName()
			+ " caught", e);
	    }
	}
    }

    protected boolean addUnsynchronized(final Object o) throws KeeperException,
	    InterruptedException, IOException {
	this.keeper.create(this.znode + "/entry-",
		Transformer.objectToBytes(o, this.elementClass), this.acl,
		this.createMode);

	return true;
    }

    protected boolean removeUnsynchronized(final int index)
	    throws InterruptedException, KeeperException {
	this.keeper.delete(
		this.znode
			+ '/'
			+ this.keeper.getChildren(this.znode, this.watcher)
				.get(index - 1), -1);

	return true;
    }

    protected boolean removeUnsynchronized(final Object o)
	    throws InterruptedException, KeeperException, IOException {
	for (final String s : this.keeper.getChildren(this.znode, this.watcher))
	    if (Arrays.equals(
		    this.keeper.getData(this.znode + '/' + s, false, null),
		    Transformer.objectToBytes(o, this.elementClass))) {
		this.keeper.delete(this.znode + '/' + s, -1);

		return true;
	    }

	return false;
    }

    /**
     * {@inheritDoc} NB: Nulls cannot be represented by this collection.
     * Attempting to add one will cause an {@link IllegalArgumentException} to
     * be thrown.
     */
    @Override
    public boolean add(final T o) {
	if (o == null)
	    throw new IllegalArgumentException("nulls not allowed");

	try {
	    return this.addUnsynchronized(o);
	} catch (final Exception e) {
	    throw new RuntimeException(
		    e.getClass().getSimpleName() + " caught", e);
	}
    }

    /**
     * {@inheritDoc} NB: Nulls cannot be represented by this collection.
     * Attempting to add one will cause an {@link IllegalArgumentException} to
     * be thrown.
     */
    @Override
    public boolean addAll(final Collection<? extends T> c) {
	boolean modified = false;

	for (final Object o : c) {
	    @SuppressWarnings("unchecked")
	    final T t = (T) o;

	    if (this.add(t))
		modified = true;
	}

	return modified;
    }

    @Override
    public void clear() {
	synchronized (this.elements) {
	    try {
		for (final String s : this.keeper.getChildren(this.znode,
			this.watcher))
		    this.keeper.delete(this.znode + '/' + s, -1);

		this.synchronize();
	    } catch (final Exception e) {
		throw new RuntimeException(e.getClass().getSimpleName()
			+ " caught", e);
	    }
	}
    }

    @Override
    public boolean contains(final Object o) {
	return this.elements.contains(o);
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
	synchronized (this.elements) {
	    for (final Object o : c)
		if (!this.elements.contains(o))
		    return false;

	    return true;
	}
    }

    @Override
    public boolean isEmpty() {
	return this.elements.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
	return new Iterator<T>() {
	    private int i;

	    @Override
	    public boolean hasNext() {
		return this.i < KeptCollection.this.elements.size();
	    }

	    @Override
	    public T next() {
		return KeptCollection.this.elements.get(this.i++);
	    }

	    @Override
	    public void remove() {
		try {
		    KeptCollection.this.removeUnsynchronized(this.i);
		} catch (final Exception e) {
		    throw new RuntimeException(e.getClass().getSimpleName()
			    + " caught", e);
		}
	    }
	};
    }

    @Override
    public boolean remove(final Object o) {
	try {
	    return this.removeUnsynchronized(o);
	} catch (final Exception e) {
	    throw new RuntimeException(
		    e.getClass().getSimpleName() + " caught", e);
	}
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
	synchronized (this.elements) {
	    try {
		boolean modified = false;

		for (final Object o : c)
		    if (this.removeUnsynchronized(o))
			modified = true;

		return modified;
	    } catch (final Exception e) {
		throw new RuntimeException(e.getClass().getSimpleName()
			+ " caught", e);
	    }
	}
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
	synchronized (this.elements) {
	    try {
		// try not to copy unless necessary
		Set<? extends Object> thatset;
		if (c instanceof Set<?>)
		    thatset = (Set<? extends Object>) c;
		else
		    thatset = new HashSet<Object>(c);

		boolean changed = false;

		for (final Object o : this.elements)
		    if (!thatset.contains(o) && this.removeUnsynchronized(o)
			    && !changed)
			changed = true;

		return changed;
	    } catch (final Exception e) {
		throw new RuntimeException(e.getClass().getSimpleName()
			+ " caught", e);
	    }
	}
    }

    @Override
    public int size() {
	return this.elements.size();
    }

    @Override
    public Object[] toArray() {
	return this.elements.toArray();
    }

    @SuppressWarnings("hiding")
    @Override
    public <T> T[] toArray(final T[] a) {
	return this.elements.toArray(a);
    }

    @Override
    public String toString() {
	return this.elements.toString();
    }
}
