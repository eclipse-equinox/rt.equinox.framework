/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.container;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleReference;
import org.osgi.service.resolver.ResolutionException;

/**
 * A module represents a set of revisions installed in a
 * module {@link ModuleContainer container}.
 */
public abstract class Module implements BundleReference {
	/**
	 * The possible start options for a module
	 */
	public static enum START_OPTIONS {
		/**
		 * The module start operation is transient and the persistent 
		 * autostart or activation policy setting of the module is not modified.
		 */
		TRANSIENT,
		/**
		 * The module start operation must activate the module according to the module's declared
		 * activation policy.
		 */
		ACTIVATION_POLICY,
		/**
		 * The module start operation that indicates the module is being started because of a
		 * lazy start trigger class load.  This option must be used with the 
		 * {@link START_OPTIONS#TRANSIENT transient} options.
		 */
		LAZY_TRIGGER
	}

	/**
	 * The possible start options for a module
	 */
	public static enum STOP_OPTIONS {
		/**
		 * The module stop operation is transient and the persistent 
		 * autostart setting of the module is not modified.
		 */
		TRANSIENT
	}

	/**
	 * An enumeration of the possible {@link Module#getState() states} a module may be in.
	 */
	public static enum State {
		/**
		 * The module is installed but not yet resolved.
		 */
		INSTALLED,
		/**
		 * The module is resolved and able to be started.
		 */
		RESOLVED,
		/**
		 * The module is waiting for a {@link START_OPTIONS#LAZY_TRIGGER trigger}
		 * class load to proceed with starting.
		 */
		LAZY_STARTING,
		/**
		 * The module is in the process of starting.
		 */
		STARTING,
		/**
		 * The module is now running.
		 */
		ACTIVE,
		/**
		 * The module is in the process of stopping
		 */
		STOPPING,
		/**
		 * The module is uninstalled and may not be used.
		 */
		UNINSTALLED
	}

	/**
	 * A set of {@link State states} that indicate a module is active.
	 */
	public static final EnumSet<State> ACTIVE_SET = EnumSet.of(State.STARTING, State.LAZY_STARTING, State.ACTIVE, State.STOPPING);
	/**
	 * A set of {@link State states} that indicate a module is resolved.
	 */
	public static final EnumSet<State> RESOLVED_SET = EnumSet.of(State.RESOLVED, State.STARTING, State.LAZY_STARTING, State.ACTIVE, State.STOPPING);

	/**
	 * Event types that may be {@link Module#fireEvent(Event) fired} for a module
	 * indicating a {@link Module#getState() state} change has occurred for a module.
	 */
	public static enum Event {
		/**
		 * The module has been installed
		 */
		INSTALLED,
		/**
		 * The module has been activated with the lazy activation policy and
		 * is waiting a {@link START_OPTIONS#LAZY_TRIGGER trigger} class load.
		 */
		LAZY_ACTIVATION,
		/**
		 * The module has been resolved.
		 */
		RESOLVED,
		/**
		 * The module has beens started.
		 */
		STARTED,
		/**
		 * The module is about to be activated.
		 */
		STARTING,
		/**
		 * The module has been stopped.
		 */
		STOPPED,
		/**
		 * The module is about to be deactivated.
		 */
		STOPPING,
		/**
		 * The module has been uninstalled.
		 */
		UNINSTALLED,
		/**
		 * The module has been unresolved.
		 */
		UNRESOLVED,
		/**
		 * The module has been updated.
		 */
		UPDATED
	}

	private final Long id;
	private final String location;
	private final ModuleRevisions revisions;
	private final ReentrantLock stateChangeLock = new ReentrantLock();
	private final EnumSet<Event> stateTransitionEvents = EnumSet.noneOf(Event.class);
	private volatile State state = State.INSTALLED;

	/**
	 * Constructs a new module with the specified id, location and
	 * container.
	 * @param id the new module id
	 * @param location the new module location
	 * @param container the container for the new module
	 */
	public Module(Long id, String location, ModuleContainer container) {
		this.id = id;
		this.location = location;
		this.revisions = new ModuleRevisions(this, container);
	}

	/**
	 * Returns the module id.
	 * @return the module id.
	 */
	public Long getId() {
		return id;
	}

	/** Returns the module location
	 * @return the module location
	 */
	public String getLocation() {
		return location;
	}

	/**
	 * Returns the {@link ModuleRevisions} associated with this module.
	 * @return the {@link ModuleRevisions} associated with this module
	 */
	public final ModuleRevisions getRevisions() {
		return revisions;
	}

	/**
	 * Returns the current {@link ModuleRevision revision} associated with this module.
	 * @return the current {@link ModuleRevision revision} associated with this module.
	 */
	public final ModuleRevision getCurrentRevision() {
		return revisions.getCurrentRevision();
	}

	/**
	 * Returns the current {@link State state} of the module.
	 * @return the current state of the module.
	 */
	public State getState() {
		return state;
	}

	void setState(State state) {
		this.state = state;
	}

	private static final EnumSet<Event> VALID_RESOLVED_TRANSITION = EnumSet.of(Event.STARTED);
	private static final EnumSet<Event> VALID_STOPPED_TRANSITION = EnumSet.of(Event.UPDATED, Event.UNRESOLVED, Event.UNINSTALLED);

	/**
	 * Acquires the module lock for state changes by the current thread for the specified
	 * transition event.  Certain transition events locks may be nested within other
	 * transition event locks.  For example, a resolved transition event lock may be
	 * nested within a started transition event lock.  A stopped transition lock
	 * may be nested within an updated, unresolved or uninstalled transition lock.
	 * @param transitionEvent the transition event to acquire the lock for.
	 * @throws BundleException
	 */
	protected void lockStateChange(Event transitionEvent) throws BundleException {
		try {
			boolean acquired = stateChangeLock.tryLock(5, TimeUnit.SECONDS);
			if (acquired) {
				boolean isValidTransition = true;
				switch (transitionEvent) {
					case STARTED :
					case UPDATED :
					case UNINSTALLED :
					case UNRESOLVED :
						// These states must be initiating transition states
						// no other transition state is allowed when these are kicked off
						isValidTransition = stateTransitionEvents.isEmpty();
						break;
					case RESOLVED :
						isValidTransition = VALID_RESOLVED_TRANSITION.containsAll(stateTransitionEvents);
						break;
					case STOPPED :
						isValidTransition = VALID_STOPPED_TRANSITION.containsAll(stateTransitionEvents);
						break;
					default :
						isValidTransition = false;
						break;
				}
				if (!isValidTransition) {
					stateChangeLock.unlock();
				} else {
					stateTransitionEvents.add(transitionEvent);
					return;
				}
			}
			throw new BundleException("Unable to acquire the state change lock for the module: " + transitionEvent, BundleException.STATECHANGE_ERROR);
		} catch (InterruptedException e) {
			throw new BundleException("Unable to acquire the state change lock for the module.", BundleException.STATECHANGE_ERROR, e);
		}
	}

	/**
	 * Releases the lock for state changes for the specified transition event.
	 * @param transitionEvent
	 */
	protected void unlockStateChange(Event transitionEvent) {
		if (stateChangeLock.getHoldCount() == 0 || !stateTransitionEvents.contains(transitionEvent))
			throw new IllegalMonitorStateException("Current thread does not hold the state change lock for: " + transitionEvent);
		stateTransitionEvents.remove(transitionEvent);
		stateChangeLock.unlock();
	}

	/**
	 * Starts this module
	 * @param options the options for starting
	 * @throws BundleException if an errors occurs while starting
	 */
	public void start(EnumSet<START_OPTIONS> options) throws BundleException {
		if (options == null)
			options = EnumSet.noneOf(START_OPTIONS.class);
		if (options.contains(START_OPTIONS.LAZY_TRIGGER) && !options.contains(START_OPTIONS.TRANSIENT))
			throw new IllegalArgumentException("Cannot use lazy trigger option without the transient option.");
		Event event;
		if (options.contains(START_OPTIONS.LAZY_TRIGGER)) {
			if (stateChangeLock.getHoldCount() > 0 && stateTransitionEvents.contains(Event.STARTED)) {
				// nothing to do here; the current thread is activating the bundle.
			}
		}
		lockStateChange(Event.STARTED);
		try {
			checkValid();
			// TODO need a check to see if the current revision is valid for start (e.g. is fragment).
			if (!options.contains(START_OPTIONS.TRANSIENT)) {
				persistStartOptions(options);
			}
			if (State.ACTIVE.equals(getState()))
				return;
			if (getState().equals(State.INSTALLED)) {
				try {
					getRevisions().getContainer().resolve(Arrays.asList(this), true);
				} catch (ResolutionException e) {
					throw new BundleException("Could not resolve module.", BundleException.RESOLVE_ERROR, e);
				}
			}
			if (getState().equals(State.INSTALLED)) {
				throw new BundleException("Could not resolve module.", BundleException.RESOLVE_ERROR);
			}
			event = doStart(options);
		} finally {
			unlockStateChange(Event.STARTED);
		}

		if (event != null) {
			if (!EnumSet.of(Event.STARTED, Event.LAZY_ACTIVATION, Event.STOPPED).contains(event))
				throw new IllegalStateException("Wrong event type: " + event);
			fireEvent(event);
		}
	}

	/**
	 * Stops this module.
	 * @param options options for stopping
	 * @throws BundleException if an error occurs while stopping
	 */
	public void stop(EnumSet<STOP_OPTIONS> options) throws BundleException {
		if (options == null)
			options = EnumSet.noneOf(STOP_OPTIONS.class);
		Event event;
		BundleException stopError = null;
		lockStateChange(Event.STOPPED);
		try {
			checkValid();
			if (!options.contains(STOP_OPTIONS.TRANSIENT)) {
				persistStopOptions(options);
			}
			if (!Module.ACTIVE_SET.contains(getState()))
				return;
			try {
				event = doStop(options);
			} catch (BundleException e) {
				stopError = e;
				// must always fire the STOPPED event
				event = Event.STOPPED;
			}
		} finally {
			unlockStateChange(Event.STOPPED);
		}

		if (event != null) {
			if (!Event.STOPPED.equals(event))
				throw new IllegalStateException("Wrong event type: " + event);
			fireEvent(event);
		}
		if (stopError != null)
			throw stopError;
	}

	private void checkValid() {
		if (getState().equals(State.UNINSTALLED))
			throw new IllegalStateException("Module has been uninstalled.");
	}

	private Event doStart(EnumSet<START_OPTIONS> options) throws BundleException {
		boolean isLazyTrigger = options.contains(START_OPTIONS.LAZY_TRIGGER);
		if (isLazyTrigger) {
			if (!State.LAZY_STARTING.equals(getState())) {
				// need to make sure we transition through the lazy starting state
				setState(State.LAZY_STARTING);
				// need to fire the lazy event
				unlockStateChange(Event.STARTED);
				try {
					fireEvent(Event.LAZY_ACTIVATION);
				} finally {
					lockStateChange(Event.STARTED);
				}
				if (State.ACTIVE.equals(getState())) {
					// A sync listener must have caused the bundle to activate
					return null;
				}
				// continue on to normal starting
			}
		} else {
			if (isLazyActivate()) {
				if (State.LAZY_STARTING.equals(getState())) {
					// a sync listener must have tried to start this module again with the lazy option
					return null; // no event to fire; nothing to do
				}
				setState(State.LAZY_STARTING);
				return Event.LAZY_ACTIVATION;
			}
		}
		setState(State.STARTING);
		fireEvent(Event.STARTING);
		try {
			startWorker(options);
			setState(State.ACTIVE);
			return Event.STARTED;
		} catch (Throwable t) {
			if (t instanceof BundleException)
				throw (BundleException) t;
			throw new BundleException("Error starting module.", BundleException.ACTIVATOR_ERROR, t);
		}
	}

	/**
	 * Performs any work associated with starting a module.  For example,
	 * loading and calling start on an activator.
	 * @param options
	 */
	protected void startWorker(EnumSet<START_OPTIONS> options) {
		// do nothing
	}

	private Event doStop(EnumSet<STOP_OPTIONS> options) throws BundleException {
		setState(State.STOPPING);
		fireEvent(Event.STOPPING);
		try {
			stopWorker(options);
			return Event.STOPPED;
		} catch (Throwable t) {
			if (t instanceof BundleException)
				throw (BundleException) t;
			throw new BundleException("Error stopping module.", BundleException.ACTIVATOR_ERROR, t);
		} finally {
			// must always set the state to stopped
			setState(State.RESOLVED);
		}
	}

	/**
	 * @throws BundleException  
	 */
	protected void stopWorker(EnumSet<STOP_OPTIONS> options) throws BundleException {
		// do nothing
	}

	/**
	 * @throws BundleException  
	 */
	protected void updateWorker(ModuleRevisionBuilder builder) throws BundleException {
		// do nothing
	}

	public String toString() {
		return "[id=" + id + "]";
	}

	/**
	 * Publishes the specified event for this module.
	 * @param event the event type to publish
	 */
	abstract protected void fireEvent(Event event);

	abstract protected void persistStartOptions(EnumSet<START_OPTIONS> options);

	abstract protected void persistStopOptions(EnumSet<STOP_OPTIONS> options);

	abstract protected void cleanup(ModuleRevision revision);

	abstract protected boolean isLazyActivate();
}
