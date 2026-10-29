# AutoFare — what we need from a driver

Everything below exists to answer one question per app:
**"When an offer appears, where exactly is the price, where is the pickup time, and
where is the Accept button?"**

Nothing in the auto-accept engine can be written without that.

---

## ⭐ The one session that matters

If you can get a driver's phone to the PC for **20 minutes while he's online and
receiving offers**, that single session replaces almost everything else on this list.

Plug it in with USB debugging on, and while offers come in I can capture — automatically —
the full structure of every offer screen: every text element, its exact ID, its position,
and which button accepts. That is the raw material for the detector.

It does not require installing AutoFare. It does not touch his account.

**Best time:** peak hours, so offers actually arrive. 4–7pm.

---

## 1. Per app — the offer screen  🔴 blocking

For **each** app you want to support (Jeeny, Petra Ride, Careem, Uber, …):

- [ ] **Screenshot of the offer popup** — the moment it appears, before accepting
- [ ] **Screen recording** of the full flow: offer appears → accept → what the screen does next
- [ ] The app's **exact version** (Settings → About in the driver app)

### Variants — this is where detectors break
Normal offers are easy. Get me the awkward ones:

- [ ] An offer **with no price shown**
- [ ] A **surge / bonus** offer (extra badge, multiplier, different colour)
- [ ] A **long-distance** offer
- [ ] A **scheduled / later** booking, if the app has them
- [ ] A **shared / pool** ride, if the app has them
- [ ] An offer arriving **while already on a trip**
- [ ] The screen in **both Arabic and English**, if the driver can switch
- [ ] **Dark mode**, if the driver app has it

Every variant I never see is a case where AutoFare does nothing, or worse, misreads
the price.

---

## 2. Per app — technical  🟠 important

- [ ] **Package name** (e.g. `com.jeeny.driver`) — I can read these off the phone in one
      command if it's connected; I only guessed them in the code
- [ ] Is the offer a **full screen**, a **popup over the map**, or a **notification**?
- [ ] Is there a **countdown**, and **how many seconds** before it disappears?
- [ ] Does accepting need a **tap**, a **swipe**, or a **hold**?
- [ ] Does a **confirmation** appear after accepting?

---

## 3. Questions for the driver — the algorithm  🟠 important

This is what makes AutoFare smarter than the competition. The competitor only knows
"price above X". We want to encode how a good driver actually thinks.

**What's on the offer**
- [ ] Does it show the **price before** accepting? Always, or only sometimes?
- [ ] Does it show **time to the customer**, **distance to the customer**, or both?
- [ ] Does it show the **trip length** or the **destination**?
- [ ] Is the price shown **before or after** the company's commission?
- [ ] Does it show the **customer's rating**?

**How he decides**
- [ ] What makes an offer **worth taking**? Ask him to say it in his own words.
- [ ] What does he **always refuse**, and why?
- [ ] What's a normal price range — morning, evening, night?
- [ ] Which **areas** does he avoid, and why?
- [ ] What are his **best hours**?
- [ ] Does he think in price, or price-per-minute? (This decides whether our headline
      feature matches how he actually works.)

**The rules of the platform**
- [ ] Is there an **acceptance-rate** requirement? What happens below it?
- [ ] What's the penalty for **cancelling after accepting**?
- [ ] How many **seconds** does he really have to decide?
- [ ] Has he heard of drivers being **banned for using auto-accept apps**?

That last one is not optional. We need to know what we're exposing users to.

---

## 4. Test access  🟡 ongoing

- [ ] A driver willing to run AutoFare for **a few shifts** and report back
- [ ] His **phone brand and Android version** (battery-killing behaviour differs wildly —
      Huawei and Xiaomi are the worst)
- [ ] Agreement to tell us **immediately** when a driver app updates and AutoFare
      stops working

**Be honest with him up front:** ride companies forbid auto-accept. He is taking a real
risk with his account. A driver who knows that and accepts it is a good tester. One who
finds out later is a problem.

---

## 5. What I do NOT need

So nobody worries:

- ❌ His password or login
- ❌ His personal details, phone number, or ID
- ❌ Access to his earnings or bank details
- ❌ His phone permanently

Only what the offer screen looks like.

---

## Priority order

1. **One offer screenshot from one app** → I can start the detector
2. **The 20-minute live capture session** → I can finish it properly
3. **The variant screenshots** → it stops breaking
4. **The driver's answers in section 3** → it gets smarter than the competition
5. **A tester for a few shifts** → it becomes shippable
