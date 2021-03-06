package fpinscala.state


trait RNG {
  def nextInt: (Int, RNG) // Should generate a random `Int`. We'll later define other functions in terms of `nextInt`.
}

object RNG {
  // NB - this was called SimpleRNG in the book text

  case class Simple(seed: Long) extends RNG {
    def nextInt: (Int, RNG) = {
      val newSeed = (seed * 0x5DEECE66DL + 0xBL) & 0xFFFFFFFFFFFFL // `&` is bitwise AND. We use the current seed to generate a new seed.
      val nextRNG = Simple(newSeed) // The next state, which is an `RNG` instance created from the new seed.
      val n = (newSeed >>> 16).toInt // `>>>` is right binary shift with zero fill. The value `n` is our new pseudo-random integer.
      (n, nextRNG) // The return value is a tuple containing both a pseudo-random integer and the next `RNG` state.
    }
  }

  type Rand[+A] = RNG => (A, RNG)

  val int: Rand[Int] = _.nextInt

  def unit[A](a: A): Rand[A] =
    rng => (a, rng)

  def map[A,B](s: Rand[A])(f: A => B): Rand[B] =
    rng => {
      val (a, rng2) = s(rng)
      (f(a), rng2)
    }

  // 6.1
  def nonNegativeInt(rng: RNG): (Int, RNG) = {
    val (v, r) = rng.nextInt
    (if (v < 0 ) -(v + 1) else v, r)
  }

  // 6.2, 6.5
  val double: Rand[Double] = map(nonNegativeInt)(_ / (Int.MaxValue.toDouble + 1))


  // 6.3
  def intDouble(rng: RNG): ((Int,Double), RNG) = {
    val (i, g1) = rng.nextInt
    val (d, g2) = double(g1)
    ((i,d), g2)
  }

  def doubleInt(rng: RNG): ((Double,Int), RNG) = {
    val ((i, d), r) = intDouble(rng)
    ((d, i), r)
  }

  def double3(rng: RNG): ((Double,Double,Double), RNG) = {
    val (d1, r1) = double(rng)
    val (d2, r2) = double(r1)
    val (d3, r3) = double(r2)
    ((d1, d2, d3), r3)
  }

  def boolean(rng: RNG): (Boolean, RNG) =
    rng.nextInt match { case (i,rng2) => (i%2==0,rng2) }

  // 6.4
  def ints(count: Int)(rng: RNG): (List[Int], RNG) =
    (0 until count).foldLeft((List[Int](), rng)) { (p, _) =>
      val (i, r) = p._2.nextInt
      (i :: p._1, r)
    }

  // 6.6
  def map2[A,B,C](ra: Rand[A], rb: Rand[B])(f: (A, B) => C): Rand[C] = {
    rng => {
      val (a, r1) = ra(rng)
      val (b, r2) = rb(r1)
      (f(a, b), r2)
    }
  }

  // 6.7
  def sequence[A](fs: List[Rand[A]]): Rand[List[A]] =
    fs.foldRight(unit(List[A]()))((f, acc) => map2(f, acc)(_ :: _))

  def _ints(count: Int): Rand[List[Int]] =
    sequence(List.fill(count)(int))

  // 6.8
  def flatMap[A,B](f: Rand[A])(g: A => Rand[B]): Rand[B] =
    rng => {
      val (a, r) = f(rng)
      g(a)(r)
    }

  def nonNegativeLessThan(n: Int): Rand[Int] =
    flatMap(nonNegativeInt) { i =>
      val mod = i % n
      if (i + (n-1) - mod >= 0) unit(mod) else nonNegativeLessThan(n) // ???
    }

  // 6.9
  def mapViaFlatmap[A,B](s: Rand[A])(f: A => B): Rand[B] =
    flatMap(s) { a => unit(f(a)) }

  def map2ViaFlatmap[A,B,C](ra: Rand[A], rb: Rand[B])(f: (A, B) => C): Rand[C] =
    flatMap(ra)(a => map(rb)(b => f(a, b)))
}

import State._

case class State[S,+A](run: S => (A, S)) {
  // 6.10
  def map[B](f: A => B): State[S, B] =
    flatMap(a => State.unit(f(a)))

  def map2[B,C](sb: State[S, B])(f: (A, B) => C): State[S, C] =
    flatMap(a => sb.map(b => f(a, b)))

  def flatMap[B](f: A => State[S, B]): State[S, B] = State(s => {
    val (a, s2) = run(s)
    f(a).run(s2)
  })
}

object State {
  // 6.10
  def unit[S, A](a: A): State[S, A] = State( s => (a, s))

  def sequence[S, A](sas: List[State[S, A]]): State[S, List[A]] =
    sas.foldRight(unit[S, List[A]](List()))((s, acc) => s.map2(acc)(_ :: _))

  type Rand[A] = State[RNG, A]

  def modify[S](f: S => S): State[S, Unit] = for {
    s <- get // Gets the current state and assigns it to `s`.
    _ <- set(f(s)) // Sets the new state to `f` applied to `s`.
  } yield ()

  def get[S]: State[S, S] = State(s => (s, s))

  def set[S](s: S): State[S, Unit] = State(_ => ((), s))
}

sealed trait Input
case object Coin extends Input
case object Turn extends Input

case class Machine(locked: Boolean, candies: Int, coins: Int)

object CandyDispenser {

  def update: Input => Machine => Machine = i => m => {
    (i, m) match {
      case (Turn, Machine(true, _, _)) => m
      case (Coin, Machine(false, _, _)) => m
      case (_, Machine(_, 0, _)) => m
      case (Coin, Machine(true, c, cc)) => Machine(locked = false, c, cc + 1)
      case (Turn, Machine(false, c, cc)) => Machine(locked = true, c - 1, cc)
    }
  }

  def simulateMachine(inputs: List[Input]): State[Machine, (Int, Int)] = for {
    _ <- sequence(inputs map (modify[Machine] _ compose update))
    s <- get
  } yield (s.coins, s.candies)

  /*
  isn't it much simpler ?!
  why do we need State if Machine is already like a state and the result is inside Machine anyway ?
  comparing to the book solution - we don't need 'sequence', 'modify' and 'get'
  just composing 'update'(s) - anyway original solution is composing the same 'update'(s),
  but foldLeft easier to read
   */
  def simulateMachine2(inputs: List[Input]): Machine => Machine =
    inputs.foldLeft((m: Machine) => m) (_ compose update(_))
}
