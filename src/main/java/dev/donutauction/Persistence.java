package dev.donutauction;

/** What the engine needs from storage (implemented by AuctionStore). */
public interface Persistence {
	void saveCurrent(Auction a);

	void clearCurrent();

	void appendHistory(Auction a);
}
